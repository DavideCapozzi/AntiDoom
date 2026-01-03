package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.content.pm.ActivityInfo
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
        private const val UI_UPDATE_INTERVAL_MS = 2000L
        private const val DB_FLUSH_INTERVAL_MS = 30_000L
        private const val MIN_DIST_TO_UPDATE_UI = 5f

        // CRITICAL TUNING:
        // 800ms è il "Magic Number". È sufficiente per coprire l'animazione di chiusura (evita Double Overlay)
        // ma abbastanza breve da sembrare istantaneo se l'utente riapre l'app subito.
        private const val POST_EXIT_IMMUNITY_MS = 800L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    // --- DATA & CONFIG ---
    @Volatile private var trackedAppsCache: Set<String> = emptySet()
    @Volatile private var appLimitsCache: Map<String, Float> = emptyMap()
    @Volatile private var globalLimitMeters: Float = 100f

    @Volatile private var currentDailyTotal: Float = 0f
    private var dailyAppUsageCache: Map<String, Float> = emptyMap()

    // --- SESSION STATE ---
    @Volatile private var currentAppPackage: String = ""
    private var sessionAccumulatedMeters = 0f
    private var lastUiUpdateTimestamp = 0L
    private var lastUiUpdateDistance = 0f

    // --- IMMUNITY CONTROL ---
    // Uptime timestamp fino al quale ignoriamo STRICTLY i pacchetti bloccati.
    @Volatile private var immunityDeadline: Long = 0L
    private var lastBlockedPackage: String = ""

    // --- UI STATE ---
    private var isOverlayShowing = false
    private var currentOverlayView: View? = null
    private var currentOverlayType: OverlayType? = null

    // --- ENFORCEMENT STATE ---
    private data class EnforcementState(
        var warned25: Boolean = false,
        var softBlocked50: Boolean = false,
        var hardBlocked: Boolean = false
    )
    private val enforcementStates = mutableMapOf<String, EnforcementState>()
    private var lastCheckDate: String = ""

    // Event buffering
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
            lastCheckDate = LocalDate.now().toString()
            enforcementStates["GLOBAL"] = EnforcementState()

            startEventConsumer()
            startPeriodicFlushing()
            startStateObservation()
            startForegroundServiceSafe()
        } catch (e: Exception) {
            Log.e("AntiDoom", "Service init error", e)
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
                if (today != lastCheckDate) {
                    enforcementStates.clear()
                    enforcementStates["GLOBAL"] = EnforcementState()
                    lastCheckDate = today
                }
                repository.getDailyAppDistancesFlow(today)
                    .distinctUntilChanged()
                    .collectLatest { map ->
                        dailyAppUsageCache = map
                        currentDailyTotal = map.values.sum()
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

        // 1. IMMUNITY CHECK (Fast Path)
        // Se siamo nella "Deadzone" post-uscita, ignora tutto da quell'app.
        // Questo elimina i "Ghost Events" durante l'animazione di chiusura.
        if (pkgName == lastBlockedPackage && SystemClock.uptimeMillis() < immunityDeadline) {
            return
        }

        // 2. WINDOW STATE (Context Switch)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Se rileviamo un pacchetto diverso (es. Launcher), resettiamo immediatamente l'immunità.
            // Questo permette il rientro rapido in altre app o nello stesso pacchetto dopo un ciclo.
            if (pkgName != lastBlockedPackage && pkgName != currentAppPackage) {
                immunityDeadline = 0L // Reset Deadzone
            }
            handlePackageSwitch(pkgName)
            return
        }

        // 3. SCROLLING
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return
            if (isOverlayShowing) return

            if (pkgName != currentAppPackage) handlePackageSwitch(pkgName)

            val eventCopy = AccessibilityEvent.obtain(event)
            scrollEventChannel.trySend(eventCopy to pkgName)
        }
    }

    private fun handlePackageSwitch(newPackage: String) {
        if (newPackage == currentAppPackage) return

        // Flush old session
        serviceScope.launch {
            if (currentAppPackage.isNotEmpty() && sessionAccumulatedMeters > 0) {
                repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
            }
        }

        sessionAccumulatedMeters = 0f
        lastUiUpdateDistance = 0f
        repository.updateActiveDistance(0f)
        currentAppPackage = newPackage

        // RE-ENTRY CHECK:
        // Se l'utente rientra in un'app tracciata, verifichiamo subito i limiti.
        if (newPackage in trackedAppsCache) {
            checkEnforcementState(source = "WINDOW_SWITCH")
        } else {
            // Se usciamo da un'app tracciata, rimuoviamo overlay (safe cleanup)
            if (isOverlayShowing) removeOverlaySafe()
        }
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, pkgName) in scrollEventChannel) {
                try {
                    // Doppio controllo immunità (per eventi rimasti in coda)
                    if (pkgName == lastBlockedPackage && SystemClock.uptimeMillis() < immunityDeadline) {
                        continue
                    }
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
        if (now - lastUiUpdateTimestamp > UI_UPDATE_INTERVAL_MS ||
            abs(sessionAccumulatedMeters - lastUiUpdateDistance) > MIN_DIST_TO_UPDATE_UI
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
     * CORE LOGIC: Valuta i limiti e applica i blocchi.
     * Include il controllo "Reality Check" per evitare falsi positivi.
     */
    private fun checkEnforcementState(source: String) {
        if (currentAppPackage !in trackedAppsCache) {
            if (isOverlayShowing) removeOverlaySafe()
            return
        }

        // Calcolo utilizzo totale
        val currentAppUsage = (dailyAppUsageCache[currentAppPackage] ?: 0f) + sessionAccumulatedMeters
        val currentTotalUsage = currentDailyTotal + sessionAccumulatedMeters

        // 1. GLOBAL LIMIT
        val globalLimit = globalLimitMeters
        if (currentTotalUsage >= globalLimit) {
            tryShowBlockOverlay(
                OverlayType.HARD,
                "⛔ LIMIT REACHED",
                "Global budget exhausted.",
                "GO TO HOME",
                currentAppPackage
            )
            return
        }

        // 2. APP LIMIT
        val appLimit = appLimitsCache[currentAppPackage]
        if (appLimit != null) {
            if (currentAppUsage >= appLimit) {
                tryShowBlockOverlay(
                    OverlayType.HARD,
                    "⛔ APP LIMIT REACHED",
                    "Limit for this app reached.",
                    "CLOSE APP",
                    currentAppPackage
                )
                return
            }
        }

        // Se siamo qui, i limiti sono rispettati. Se l'overlay era mostrato, rimuovilo.
        if (isOverlayShowing) {
            removeOverlaySafe()
        }
    }

    private fun tryShowBlockOverlay(
        type: OverlayType,
        title: String,
        msg: String,
        btn: String,
        targetPackage: String
    ) {
        // Evita flickering se già mostrato
        if (isOverlayShowing && currentOverlayType == type) return

        // REALITY CHECK (LATERAL THINKING SOLUTION):
        // Prima di bloccare, verifichiamo: "L'app target è DAVVERO la finestra attiva?"
        // Questo interroga il sistema operativo direttamente, bypassando la coda eventi.
        // Se stiamo ricevendo eventi fantasma mentre l'app si chiude, rootInActiveWindow
        // sarà null (transizione) o il Launcher. NON sarà targetPackage.
        if (!isPackageActuallyVisible(targetPackage)) {
            Log.d("AntiDoom", "Block aborted: $targetPackage is not actually visible.")
            return
        }

        showOverlay(ActionRequest(type, title, msg, btn, Color.parseColor("#F2000000")))
    }

    /**
     * Verifica la "Ground Truth" chiedendo al sistema quale finestra è attiva.
     * Costoso, da usare solo prima di applicare un blocco.
     */
    private fun isPackageActuallyVisible(targetPkg: String): Boolean {
        return try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                // Se null, siamo in transizione o system UI. Sicuramente NON è l'app attiva stabile.
                false
            } else {
                val actualPkg = rootNode.packageName?.toString()
                // Ricicla il nodo per evitare memory leaks
                // N.B. In alcune versioni API recycle() non è necessario ma è buona prassi
                // rootNode.recycle() // (Commentato per sicurezza su alcune ROM custom)
                actualPkg == targetPkg
            }
        } catch (e: Exception) {
            // Fallback permissivo in caso di errore API: assumiamo sia visibile per non rompere la feature
            true
        }
    }

    // --- OVERLAY MANAGEMENT ---

    data class ActionRequest(val type: OverlayType, val title: String, val message: String, val btnText: String, val color: Int)
    enum class OverlayType { HARD, SOFT }

    private fun showOverlay(action: ActionRequest) {
        if (!Settings.canDrawOverlays(this)) return

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
            // ACTION: EXIT

            // 1. Imposta Immunità/Deadzone (800ms)
            // Qualsiasi evento da questa app sarà ignorato per 0.8s.
            lastBlockedPackage = currentAppPackage
            immunityDeadline = SystemClock.uptimeMillis() + POST_EXIT_IMMUNITY_MS

            // 2. Trigger Home
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                startActivity(homeIntent)
            } catch (e: Exception) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }

            // 3. Rimuovi Overlay
            removeOverlaySafe()

        } else {
            // Soft dismiss
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
                if (sessionAccumulatedMeters > 0 && currentAppPackage.isNotEmpty()) {
                    repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
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