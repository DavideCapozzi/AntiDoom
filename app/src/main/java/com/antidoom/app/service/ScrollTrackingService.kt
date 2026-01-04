package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antidoom.app.R
import com.antidoom.app.data.ScrollRepository
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import kotlin.math.abs

@Suppress("AccessibilityServiceInfo")
class ScrollTrackingService : AccessibilityService() {

    companion object {
        private const val PIXELS_PER_METER = 3500f
        private const val FALLBACK_SCROLL_PIXELS = 1500
        private const val UI_UPDATE_INTERVAL_MS = 1000L
        private const val DB_FLUSH_INTERVAL_MS = 30_000L
        private const val MIN_DIST_TO_UPDATE_UI = 2f
        private const val POST_EXIT_IMMUNITY_MS = 800L

        // Thresholds
        private const val THRESHOLD_WARN = 0.25f
        private const val THRESHOLD_SOFT = 0.50f
    }

    private data class EnforcementState(
        var warned25: Boolean = false,
        var softBlocked50: Boolean = false
    )
    private val enforcementStates = mutableMapOf<String, EnforcementState>()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- DATA CACHE (Volatile & Optimistic) ---
    // Usiamo mappe immutabili rimpiazzate atomicamente per thread-safety senza lock complessi
    @Volatile private var trackedAppsCache: Set<String> = emptySet()
    @Volatile private var appLimitsCache: Map<String, Float> = emptyMap()
    @Volatile private var globalLimitMeters: Float = 100f

    // Critical: Questa cache deve essere aggiornata ottimisticamente
    @Volatile private var dailyAppUsageCache: Map<String, Float> = emptyMap()
    @Volatile private var currentDailyTotal: Float = 0f

    // Session State
    @Volatile private var currentAppPackage: String = ""
    private var sessionAccumulatedMeters = 0f
    private var lastUiUpdateTimestamp = 0L
    private var lastUiUpdateDistance = 0f

    // Immunity & UI
    @Volatile private var immunityDeadline: Long = 0L
    private var lastBlockedPackage: String = ""
    private var isOverlayShowing = false
    private var currentOverlayView: View? = null
    private var currentOverlayType: OverlayType? = null

    private val scrollEventChannel = Channel<Pair<AccessibilityEvent, String>>(
        capacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun onCreate() {
        super.onCreate()
        try {
            repository = ScrollRepository.get(applicationContext)
            prefs = UserPreferences(applicationContext)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            ensureStateExists("GLOBAL")

            startEventConsumer()
            startPeriodicFlushing()
            startStateObservation()
            startForegroundServiceSafe()
        } catch (e: Exception) {
            Log.e("AntiDoom", "Service init error", e)
        }
    }

    private fun ensureStateExists(key: String) {
        if (!enforcementStates.containsKey(key)) {
            enforcementStates[key] = EnforcementState()
        }
    }

    private fun startStateObservation() {
        // Observer Config
        serviceScope.launch {
            combine(prefs.trackedApps, prefs.dailyLimit, prefs.appLimits) { apps, gl, al ->
                Triple(apps, gl, al)
            }.collectLatest { (apps, gl, al) ->
                trackedAppsCache = apps
                globalLimitMeters = gl
                appLimitsCache = al
                checkEnforcementState(source = "CONFIG")
            }
        }

        // Observer DB Usage
        serviceScope.launch {
            while (isActive) {
                val today = LocalDate.now().toString()
                repository.getDailyAppDistancesFlow(today)
                    .distinctUntilChanged()
                    .collectLatest { map ->
                        // Questo aggiorna la cache con la "Verità" dal DB.
                        // Sovrascriverà i nostri aggiornamenti ottimistici, ma va bene
                        // perché a questo punto il DB dovrebbe essere allineato.
                        dailyAppUsageCache = map
                        currentDailyTotal = map.values.sum()
                        checkEnforcementState(source = "DB_UPDATE")
                    }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 80
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return
        if (pkgName == packageName) return

        if (pkgName == lastBlockedPackage && SystemClock.uptimeMillis() < immunityDeadline) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkgName != lastBlockedPackage && pkgName != currentAppPackage) {
                immunityDeadline = 0L
            }
            handlePackageSwitch(pkgName)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return
            if (isOverlayShowing && currentOverlayType == OverlayType.HARD) return

            if (pkgName != currentAppPackage) handlePackageSwitch(pkgName)

            val eventCopy = AccessibilityEvent.obtain(event)
            scrollEventChannel.trySend(eventCopy to pkgName)
        }
    }

    /**
     * CRITICAL FIX: Optimistic Update
     */
    private fun handlePackageSwitch(newPackage: String) {
        if (newPackage == currentAppPackage) return

        val previousPkg = currentAppPackage
        val metersToFlush = sessionAccumulatedMeters

        if (previousPkg.isNotEmpty() && metersToFlush > 0) {
            // 1. Aggiornamento DB Asincrono (Lento)
            serviceScope.launch {
                repository.flushSessionToDb(previousPkg, metersToFlush)
            }

            // 2. Aggiornamento Cache LOCALE Istantaneo (Veloce)
            // Questo chiude il "Persistence Gap". Aggiorniamo la RAM subito.
            val currentMap = dailyAppUsageCache.toMutableMap()
            val oldVal = currentMap[previousPkg] ?: 0f
            currentMap[previousPkg] = oldVal + metersToFlush

            // Commit atomico alla variabile volatile
            dailyAppUsageCache = currentMap
            currentDailyTotal += metersToFlush
        }

        // Reset Sessione
        sessionAccumulatedMeters = 0f
        lastUiUpdateDistance = 0f
        repository.updateActiveDistance(0f)
        currentAppPackage = newPackage

        ensureStateExists(newPackage)

        // Controllo Limiti all'ingresso
        if (newPackage in trackedAppsCache) {
            checkEnforcementState(source = "WINDOW_SWITCH")
        } else {
            if (isOverlayShowing) removeOverlaySafe()
        }
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, pkgName) in scrollEventChannel) {
                try {
                    if (pkgName == lastBlockedPackage && SystemClock.uptimeMillis() < immunityDeadline) continue
                    processScrollEvent(event)
                } finally {
                    try { event.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun processScrollEvent(event: AccessibilityEvent) {
        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else FALLBACK_SCROLL_PIXELS
        val meters = pixelsToAdd / PIXELS_PER_METER

        sessionAccumulatedMeters += meters

        val now = SystemClock.uptimeMillis()
        if (abs(sessionAccumulatedMeters - lastUiUpdateDistance) > MIN_DIST_TO_UPDATE_UI ||
            now - lastUiUpdateTimestamp > UI_UPDATE_INTERVAL_MS
        ) {
            repository.updateActiveDistance(sessionAccumulatedMeters)
            lastUiUpdateTimestamp = now
            lastUiUpdateDistance = sessionAccumulatedMeters
            checkEnforcementState(source = "SCROLL")
        }
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    /**
     * LOGICA PRIORITÀ:
     * 1. App Limit (Specifico) -> Vince su tutto.
     * 2. Global Limit (Generico) -> Scatta se App Limit non è attivo o non raggiunto.
     */
    private fun checkEnforcementState(source: String) {
        if (currentAppPackage !in trackedAppsCache) {
            if (isOverlayShowing) removeOverlaySafe()
            return
        }

        // Calcolo Dati (Cache Aggiornata Ottimisticamente + Sessione Corrente)
        val usageGlobal = currentDailyTotal + sessionAccumulatedMeters
        val usageApp = (dailyAppUsageCache[currentAppPackage] ?: 0f) + sessionAccumulatedMeters
        val limitApp = appLimitsCache[currentAppPackage]

        // --- HARD BLOCK CHECK (Priorità Invertita) ---

        // 1. Check APP LIMIT (Massima Priorità Specificità)
        if (limitApp != null && usageApp >= limitApp) {
            triggerOverlay(
                OverlayType.HARD,
                "⛔ APP LIMIT REACHED",
                "Limit for this app reached.",
                "CLOSE APP"
            )
            return // Blocca qui, ignora Global
        }

        // 2. Check GLOBAL LIMIT (Fallback Generico)
        if (usageGlobal >= globalLimitMeters) {
            triggerOverlay(
                OverlayType.HARD,
                "⛔ LIMIT REACHED",
                "Global daily budget exhausted.",
                "GO TO HOME"
            )
            return
        }

        // --- NESSUN HARD BLOCK ATTIVO ---

        // Rimuovi Hard Overlay se i limiti non sono più superati
        if (isOverlayShowing && currentOverlayType == OverlayType.HARD) {
            removeOverlaySafe()
        }

        // --- SOFT WARNINGS (Stateful) ---

        // Gestione priorità anche per i Warning:
        // Se l'app ha un limite, mostra i warning relativi all'app.
        // Altrimenti mostra i warning globali.

        var warningHandled = false

        if (limitApp != null) {
            warningHandled = checkIntermediateStates(usageApp, limitApp, currentAppPackage)
        }

        // Se non abbiamo mostrato warning specifici per l'app, controlliamo il globale
        if (!warningHandled) {
            checkIntermediateStates(usageGlobal, globalLimitMeters, "GLOBAL")
        }
    }

    /**
     * Ritorna true se un warning è stato attivato/gestito
     */
    private fun checkIntermediateStates(usage: Float, limit: Float, key: String): Boolean {
        if (limit <= 0) return false

        ensureStateExists(key)
        val state = enforcementStates[key]!!
        val ratio = usage / limit

        if (ratio >= THRESHOLD_SOFT) {
            if (!state.softBlocked50) {
                state.softBlocked50 = true
                if (!isOverlayShowing) {
                    triggerOverlay(
                        OverlayType.SOFT,
                        "⚠️ 50% USED",
                        "You have used half of your budget for ${if(key=="GLOBAL") "today" else "this app"}.",
                        "CONTINUE"
                    )
                }
            }
            return true
        }
        else if (ratio >= THRESHOLD_WARN) {
            if (!state.warned25) {
                state.warned25 = true
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "AntiDoom: 25% of ${if(key=="GLOBAL") "Global" else "App"} limit used.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            return true
        }
        return false
    }

    private fun triggerOverlay(type: OverlayType, title: String, msg: String, btn: String) {
        if (!isPackageActuallyVisible(currentAppPackage)) {
            Log.d("AntiDoom", "Overlay trigger aborted: Target not visible.")
            return
        }
        showOverlay(ActionRequest(type, title, msg, btn, Color.parseColor("#F2000000")))
    }

    private fun isPackageActuallyVisible(targetPkg: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) false
            else rootNode.packageName?.toString() == targetPkg
        } catch (e: Exception) {
            true
        }
    }

    // --- OVERLAY MANAGEMENT ---

    data class ActionRequest(val type: OverlayType, val title: String, val message: String, val btnText: String, val color: Int)
    enum class OverlayType { HARD, SOFT }

    private fun showOverlay(action: ActionRequest) {
        if (!Settings.canDrawOverlays(this)) return

        // Anti-Flickering
        if (isOverlayShowing && currentOverlayType == action.type) return

        // Priority Protection: Mai sovrascrivere HARD con SOFT
        if (isOverlayShowing && currentOverlayType == OverlayType.HARD && action.type == OverlayType.SOFT) return

        serviceScope.launch(Dispatchers.Main) {
            if (currentOverlayView != null) removeOverlaySafe()

            isOverlayShowing = true
            currentOverlayType = action.type

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
            ).apply {
                dimAmount = 0.95f
                gravity = Gravity.CENTER
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }

            val overlayView = createOverlayView(action)
            try {
                windowManager.addView(overlayView, params)
                currentOverlayView = overlayView
            } catch (e: Exception) {
                isOverlayShowing = false
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createOverlayView(action: ActionRequest): View {
        return FrameLayout(this).apply {
            setBackgroundColor(action.color)
            isClickable = true

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val titleView = TextView(context).apply {
                text = action.title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val msgView = TextView(context).apply {
                text = action.message
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(32, 16, 32, 48)
            }

            val btn = Button(context).apply {
                text = action.btnText
                setBackgroundColor(Color.WHITE)
                setTextColor(Color.BLACK)
                setOnClickListener { handleOverlayAction(action.type) }
            }

            container.addView(titleView)
            container.addView(msgView)
            container.addView(btn)
            addView(container, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
    }

    private fun handleOverlayAction(type: OverlayType) {
        if (type == OverlayType.HARD) {
            // EXIT
            lastBlockedPackage = currentAppPackage
            immunityDeadline = SystemClock.uptimeMillis() + POST_EXIT_IMMUNITY_MS

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(homeIntent)
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            removeOverlaySafe()

        } else {
            // SOFT DISMISS
            removeOverlaySafe()
        }
    }

    private fun removeOverlaySafe() {
        if (!isOverlayShowing) return
        val v = currentOverlayView
        currentOverlayView = null
        currentOverlayType = null
        isOverlayShowing = false

        serviceScope.launch(Dispatchers.Main) {
            try {
                if (v != null && v.isAttachedToWindow) windowManager.removeView(v)
            } catch (_: Exception) { }
        }
    }

    // --- BACKGROUND UTILS ---

    private fun startPeriodicFlushing() {
        serviceScope.launch {
            while (isActive) {
                delay(DB_FLUSH_INTERVAL_MS)
                // Usiamo handlePackageSwitch per flushare e aggiornare cache correttamente anche nel periodico
                if (sessionAccumulatedMeters > 0 && currentAppPackage.isNotEmpty()) {
                    // Nota: Qui non cambiamo pacchetto, quindi chiamiamo repository diretto
                    // Ma aggiorniamo anche la cache locale per coerenza
                    val pkg = currentAppPackage
                    val dist = sessionAccumulatedMeters

                    repository.flushSessionToDb(pkg, dist)

                    // Update cache optimistic
                    val currentMap = dailyAppUsageCache.toMutableMap()
                    currentMap[pkg] = (currentMap[pkg] ?: 0f) + dist
                    dailyAppUsageCache = currentMap
                    currentDailyTotal += dist

                    sessionAccumulatedMeters = 0f
                }
            }
        }
    }

    private fun startForegroundServiceSafe() {
        val cid = "antidoom_svc"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(cid) == null) {
            nm.createNotificationChannel(NotificationChannel(cid, "AntiDoom", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, cid)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AntiDoom Active")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, 1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notif)
            }
        } catch (_: Exception) {}
    }

    override fun onInterrupt() {}
}