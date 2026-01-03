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

        // NUCLEAR IMMUNITY: 3500ms
        // Based on testing, 2000ms was insufficient to clear the "Ghost Events" and buffer.
        // We force-ignore the blocked app for this duration after dismissal.
        private const val POST_DISMISSAL_IMMUNITY_MS = 1850L

        // Throttling Configuration
        private const val UI_UPDATE_INTERVAL_MS = 2000L
        private const val DB_FLUSH_INTERVAL_MS = 30_000L
        private const val MIN_DIST_TO_UPDATE_UI = 5f
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    // --- STATE MANAGEMENT ---

    @Volatile private var trackedAppsCache: Set<String> = emptySet()
    @Volatile private var appLimitsCache: Map<String, Float> = emptyMap()
    @Volatile private var globalLimitMeters: Float = 100f

    @Volatile private var currentDailyTotal: Float = 0f
    private var dailyAppUsageCache: Map<String, Float> = emptyMap()

    @Volatile private var currentAppPackage: String = ""
    private var sessionAccumulatedMeters = 0f

    private var lastUiUpdateTimestamp = 0L
    private var lastUiUpdateDistance = 0f

    // --- IMMUNITY STATE ---
    // The timestamp until which the specific 'lastBlockedPackage' is strictly ignored.
    private var immunityEndTime: Long = 0L
    private var lastBlockedPackage: String = ""

    private var isOverlayShowing = false
    private var currentOverlayView: View? = null
    private var currentOverlayType: OverlayType? = null

    private data class EnforcementState(
        var warned25: Boolean = false,
        var softBlocked50: Boolean = false,
        var hardBlocked: Boolean = false
    )

    private val enforcementStates = mutableMapOf<String, EnforcementState>()
    private var lastCheckDate: String = ""

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
            Log.e("AntiDoom", "Service initialization failed", e)
        }
    }

    private fun startStateObservation() {
        serviceScope.launch {
            combine(
                prefs.trackedApps,
                prefs.dailyLimit,
                prefs.appLimits
            ) { apps, globalLimit, appLimits ->
                Triple(apps, globalLimit, appLimits)
            }.collectLatest { (apps, globalLimit, limits) ->
                trackedAppsCache = apps
                appLimitsCache = limits
                globalLimitMeters = globalLimit
                checkEnforcementState()
            }
        }

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
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        if (pkgName == packageName) return

        // --- NUCLEAR IMMUNITY CHECK (FAST PATH) ---
        // If we are within the immunity window AND the event comes from the app we just kicked out:
        // DROP IT IMMEDIATELY. Do not process, do not pass go.
        if (System.currentTimeMillis() < immunityEndTime && pkgName == lastBlockedPackage) {
            return
        }

        // 1. APP SWITCH / WINDOW CHANGE
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkgName != currentAppPackage) {
                serviceScope.launch { handlePackageSwitch(pkgName) }
                performFastBlockCheck(pkgName)
            }
            return
        }

        // 2. SCROLL
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return
            if (isOverlayShowing) return

            if (pkgName != currentAppPackage) {
                serviceScope.launch { handlePackageSwitch(pkgName) }
                performFastBlockCheck(pkgName)
            }

            val eventCopy = AccessibilityEvent.obtain(event)
            scrollEventChannel.trySend(eventCopy to pkgName)
        }
    }

    private fun performFastBlockCheck(targetPackage: String) {
        // Double check immunity here for safety
        if (System.currentTimeMillis() < immunityEndTime && targetPackage == lastBlockedPackage) {
            return
        }

        if (targetPackage !in trackedAppsCache) return
        if (isOverlayShowing) return

        val currentUsage = dailyAppUsageCache[targetPackage] ?: 0f
        val limit = appLimitsCache[targetPackage]

        var actionRequest: ActionRequest? = null

        if (currentDailyTotal >= globalLimitMeters) {
            actionRequest = ActionRequest(
                OverlayType.HARD,
                "⛔ LIMIT REACHED",
                "Global budget exhausted.",
                "GO TO HOME",
                Color.parseColor("#F2000000")
            )
        } else if (limit != null && currentUsage >= limit) {
            actionRequest = ActionRequest(
                OverlayType.HARD,
                "⛔ APP LIMIT REACHED",
                "Limit for this app reached.",
                "CLOSE APP",
                Color.parseColor("#F2000000")
            )
        }

        actionRequest?.let {
            serviceScope.launch(Dispatchers.Main) { showOverlay(it) }
        }
    }

    private suspend fun handlePackageSwitch(newPackage: String) {
        if (currentAppPackage.isNotEmpty() && sessionAccumulatedMeters > 0) {
            repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
        }

        sessionAccumulatedMeters = 0f
        lastUiUpdateDistance = 0f
        repository.updateActiveDistance(0f)
        currentAppPackage = newPackage

        checkEnforcementState()
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, pkgName) in scrollEventChannel) {
                try {
                    // --- NUCLEAR IMMUNITY CHECK (ASYNC PATH) ---
                    // Even if the event was queued 2 seconds ago, if we process it NOW
                    // and we are in the immunity window, we drop it.
                    if (System.currentTimeMillis() < immunityEndTime && pkgName == lastBlockedPackage) {
                        event.recycle()
                        continue
                    }

                    processScrollEvent(event, pkgName)
                } catch (e: Exception) {
                    Log.e("AntiDoom", "Error processing scroll event", e)
                } finally {
                    try { event.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun processScrollEvent(event: AccessibilityEvent, pkgName: String) {
        if (currentAppPackage != pkgName) {
            handlePackageSwitch(pkgName)
        }

        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else FALLBACK_SCROLL_PIXELS
        val meters = pixelsToAdd / PIXELS_PER_METER

        sessionAccumulatedMeters += meters

        val now = System.currentTimeMillis()
        val distDiff = abs(sessionAccumulatedMeters - lastUiUpdateDistance)

        if (now - lastUiUpdateTimestamp > UI_UPDATE_INTERVAL_MS || distDiff > MIN_DIST_TO_UPDATE_UI) {
            repository.updateActiveDistance(sessionAccumulatedMeters)
            lastUiUpdateTimestamp = now
            lastUiUpdateDistance = sessionAccumulatedMeters
            checkEnforcementState()
        }
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    private fun checkEnforcementState() {
        if (System.currentTimeMillis() < immunityEndTime && currentAppPackage == lastBlockedPackage) {
            return
        }

        if (currentAppPackage !in trackedAppsCache) {
            removeOverlaySafe()
            return
        }

        val currentAppUsage = (dailyAppUsageCache[currentAppPackage] ?: 0f) + sessionAccumulatedMeters
        val currentTotalUsage = currentDailyTotal + sessionAccumulatedMeters

        val globalState = enforcementStates.getOrPut("GLOBAL") { EnforcementState() }
        val globalAction = evaluateLimit(
            usage = currentTotalUsage,
            limit = globalLimitMeters,
            state = globalState,
            label = "Global"
        )

        if (globalAction != null) {
            executeAction(globalAction)
            return
        }

        val appLimit = appLimitsCache[currentAppPackage]
        if (appLimit != null) {
            val appState = enforcementStates.getOrPut(currentAppPackage) { EnforcementState() }
            val appAction = evaluateLimit(
                usage = currentAppUsage,
                limit = appLimit,
                state = appState,
                label = "App"
            )

            if (appAction != null) {
                executeAction(appAction)
                return
            }
        }

        // Auto-dismiss if limits are no longer met (e.g. limit increased)
        if (isOverlayShowing) {
            removeOverlaySafe()
        }
    }

    data class ActionRequest(
        val type: OverlayType,
        val title: String,
        val message: String,
        val btnText: String,
        val color: Int
    )

    enum class OverlayType { HARD, SOFT }

    private fun evaluateLimit(usage: Float, limit: Float, state: EnforcementState, label: String): ActionRequest? {
        val hardLimit = limit
        val softLimit = limit * 0.5f
        val warnLimit = limit * 0.25f

        if (usage >= hardLimit) {
            if (!state.hardBlocked) state.hardBlocked = true
            return ActionRequest(
                OverlayType.HARD,
                "⛔ $label LIMIT REACHED",
                "You've exhausted your budget (${limit.toInt()}m).",
                if (label == "App") "CLOSE APP" else "GO TO HOME",
                Color.parseColor("#F2000000")
            )
        }

        if (usage >= softLimit) {
            if (!state.softBlocked50 && !isOverlayShowing) {
                state.softBlocked50 = true
                return ActionRequest(
                    OverlayType.SOFT,
                    "⚠️ HALFWAY THERE",
                    "You've reached 50% of your $label limit.",
                    "CONTINUE SCROLLING",
                    Color.parseColor("#F2CC5500")
                )
            }
        }

        if (usage >= warnLimit && !state.warned25 && usage < softLimit) {
            state.warned25 = true
            showWarningToast("$label limit: 25% used")
        }

        return null
    }

    private fun executeAction(action: ActionRequest) {
        showOverlay(action)
    }

    private fun showWarningToast(msg: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "⚠️ AntiDoom: $msg", Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay(action: ActionRequest) {
        if (!Settings.canDrawOverlays(this)) return

        serviceScope.launch(Dispatchers.Main) {
            if (isOverlayShowing && currentOverlayType == action.type) {
                return@launch
            }

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
            }

            val overlayView = createOverlayView(action)

            try {
                windowManager.addView(overlayView, params)
                currentOverlayView = overlayView
            } catch (e: Exception) {
                Log.e("AntiDoom", "Overlay failed", e)
                isOverlayShowing = false
                currentOverlayType = null
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
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }

            val titleView = TextView(context).apply {
                text = action.title
                setTextColor(Color.WHITE)
                textSize = 28f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val messageView = TextView(context).apply {
                text = action.message
                setTextColor(Color.LTGRAY)
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 64)
            }

            val actionButton = Button(context).apply {
                text = action.btnText
                setBackgroundColor(Color.WHITE)
                setTextColor(Color.BLACK)
                setOnClickListener { handleOverlayAction(action.type) }
            }

            container.addView(titleView)
            container.addView(messageView)
            container.addView(actionButton)
            addView(container)
        }
    }

    private fun handleOverlayAction(type: OverlayType) {
        when (type) {
            OverlayType.HARD -> {
                // 1. ACTIVATE IMMUNITY
                // Force ignore this package for 3.5s. This allows closing animations
                // and stale buffered events to clear out without re-triggering the overlay.
                immunityEndTime = System.currentTimeMillis() + POST_DISMISSAL_IMMUNITY_MS
                lastBlockedPackage = currentAppPackage

                removeOverlaySafe()

                // 2. TRIGGER HOME (Explicit Intent is faster/more reliable than GlobalAction)
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    startActivity(homeIntent)
                } catch (e: Exception) {
                    // Fallback just in case
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            OverlayType.SOFT -> {
                removeOverlaySafe()
            }
        }
    }

    private fun removeOverlaySafe() {
        if (!isOverlayShowing) return

        val viewToRemove = currentOverlayView
        currentOverlayView = null
        currentOverlayType = null
        isOverlayShowing = false

        serviceScope.launch(Dispatchers.Main) {
            try {
                viewToRemove?.let {
                    if (it.isAttachedToWindow) windowManager.removeView(it)
                }
            } catch (e: Exception) {
                Log.e("AntiDoom", "Error removing overlay", e)
            }
        }
    }

    private fun startPeriodicFlushing() {
        serviceScope.launch {
            while (isActive) {
                delay(DB_FLUSH_INTERVAL_MS)
                if (sessionAccumulatedMeters > 0 && currentAppPackage.isNotEmpty()) {
                    repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
                }
            }
        }
    }

    private fun startForegroundServiceSafe() {
        val channelId = "antidoom_service_channel"
        val manager = getSystemService(NotificationManager::class.java)

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "AntiDoom Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Background scroll monitoring" }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AntiDoom Active")
            .setContentText("Monitoring your digital wellbeing")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, 1, notification,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                )
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("AntiDoom", "Failed to start foreground service", e)
        }
    }

    override fun onInterrupt() { }

    override fun onDestroy() {
        if (sessionAccumulatedMeters > 0) {
            runBlocking {
                try {
                    repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
                } catch (_: Exception) { }
            }
        }
        removeOverlaySafe()
        serviceScope.cancel()
        scrollEventChannel.close()
        super.onDestroy()
    }
}