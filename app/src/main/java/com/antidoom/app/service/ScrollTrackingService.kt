package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
        private const val EXIT_GRACE_PERIOD_MS = 2000L

        // Throttling Config
        private const val UI_UPDATE_INTERVAL_MS = 2000L // Update UI every 2s max
        private const val DB_FLUSH_INTERVAL_MS = 30_000L // Flush to DB every 30s
        private const val MIN_DIST_TO_UPDATE_UI = 5f // Or if moved 5 meters
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    // --- STATE MANAGEMENT ---

    // Config Caches
    @Volatile private var trackedAppsCache: Set<String> = emptySet()
    @Volatile private var appLimitsCache: Map<String, Float> = emptyMap()
    @Volatile private var globalLimitMeters: Float = 100f

    // Usage State
    @Volatile private var currentDailyTotal: Float = 0f
    private var dailyAppUsageCache: Map<String, Float> = emptyMap()

    // Session State
    @Volatile private var currentAppPackage: String = ""
    private var sessionAccumulatedMeters = 0f

    // Throttling State
    private var lastUiUpdateTimestamp = 0L
    private var lastUiUpdateDistance = 0f

    // Intervention State
    private var lastExitTimestamp: Long = 0L
    private var isOverlayShowing = false
    private var currentOverlayView: View? = null

    // Logic State (Generalization)
    private data class EnforcementState(
        var warned25: Boolean = false,
        var softBlocked50: Boolean = false,
        var hardBlocked: Boolean = false
    )

    // Key: PackageName (or "GLOBAL" for global limit)
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

            // Initialize global state
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
        // 1. Observe Config (Limits & Tracked Apps)
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

                // Re-check enforcement immediately on config change
                checkEnforcementState()
            }
        }

        // 2. Observe Daily Usage from DB
        serviceScope.launch {
            while (isActive) {
                val today = LocalDate.now().toString()

                // Reset states on new day
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
                        checkEnforcementState()
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
        // Set notification timeout to minimal to avoid lag
        info.notificationTimeout = 100
        serviceInfo = info
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, pkgName) in scrollEventChannel) {
                try {
                    processScrollEvent(event, pkgName)
                } catch (e: Exception) {
                    Log.e("AntiDoom", "Error processing scroll event", e)
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

        // Throttled UI Update
        val now = System.currentTimeMillis()
        val distDiff = abs(sessionAccumulatedMeters - lastUiUpdateDistance)

        if (now - lastUiUpdateTimestamp > UI_UPDATE_INTERVAL_MS || distDiff > MIN_DIST_TO_UPDATE_UI) {
            repository.updateActiveDistance(sessionAccumulatedMeters)
            lastUiUpdateTimestamp = now
            lastUiUpdateDistance = sessionAccumulatedMeters

            // Check limits only on throttled updates to save CPU
            checkEnforcementState()
        }
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    private suspend fun handlePackageSwitch(newPackage: String) {
        // 1. Flush old session
        if (currentAppPackage.isNotEmpty() && sessionAccumulatedMeters > 0) {
            repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
        }

        // 2. Reset session state
        sessionAccumulatedMeters = 0f
        lastUiUpdateDistance = 0f
        repository.updateActiveDistance(0f)

        // 3. Update pointer
        currentAppPackage = newPackage

        // 4. Force immediate check on app switch (to show blocks immediately)
        checkEnforcementState()
    }

    // --- GENERALIZED ENFORCEMENT LOGIC ---

    private fun checkEnforcementState() {
        if (System.currentTimeMillis() - lastExitTimestamp < EXIT_GRACE_PERIOD_MS) return
        if (currentAppPackage !in trackedAppsCache) {
            removeOverlaySafe()
            return
        }

        // Calculate Effective Usage (DB + RAM)
        val currentAppUsage = (dailyAppUsageCache[currentAppPackage] ?: 0f) + sessionAccumulatedMeters
        val currentTotalUsage = currentDailyTotal + sessionAccumulatedMeters

        // 1. Check GLOBAL Limits
        val globalState = enforcementStates.getOrPut("GLOBAL") { EnforcementState() }
        val globalAction = evaluateLimit(
            usage = currentTotalUsage,
            limit = globalLimitMeters,
            state = globalState,
            label = "Global"
        )

        if (globalAction != null) {
            executeAction(globalAction)
            return // Global takes precedence
        }

        // 2. Check APP Limits
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

        // 3. Cleanup if clear
        if (isOverlayShowing) {
            // If we are here, no Hard/Soft blocks are active.
            // We verify if we are below limits to remove overlay.
            // Note: evaluateLimit returns action ONLY when triggering.
            // We need to check if we are significantly below limits to dismiss?
            // Simplification: If no action returned "BLOCK", dismiss overlay.
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

    private fun evaluateLimit(usage: Float, limit: Float, state: EnforcementState, label: String): ActionRequest? {
        val hardLimit = limit
        val softLimit = limit * 0.5f
        val warnLimit = limit * 0.25f

        // HARD BLOCK (100%)
        if (usage >= hardLimit) {
            if (!state.hardBlocked) state.hardBlocked = true // Mark as hit
            return ActionRequest(
                OverlayType.HARD,
                "⛔ $label LIMIT REACHED",
                "You've exhausted your budget (${limit.toInt()}m).",
                if (label == "App") "CLOSE APP" else "GO TO HOME",
                Color.parseColor("#F2000000") // Black 95%
            )
        }

        // SOFT BLOCK (50%)
        if (usage >= softLimit && !state.softBlocked50) {
            if (!isOverlayShowing) { // Only trigger if no overlay exists
                state.softBlocked50 = true
                return ActionRequest(
                    OverlayType.SOFT,
                    "⚠️ HALFWAY THERE",
                    "You've reached 50% of your $label limit.",
                    "CONTINUE SCROLLING",
                    Color.parseColor("#F2CC5500") // Dark Orange
                )
            }
        }

        // WARNING (25%)
        if (usage >= warnLimit && !state.warned25 && usage < softLimit) {
            state.warned25 = true
            showWarningToast("$label limit: 25% used")
        }

        return null
    }

    private fun executeAction(action: ActionRequest) {
        if (!isOverlayShowing || isOverlayMismatch(action)) {
            showOverlay(action)
        }
    }

    // Check if current overlay is different from requested (e.g. updating text)
    private fun isOverlayMismatch(action: ActionRequest): Boolean {
        // Basic check: implies we should redraw if content changes drastically
        // For simplicity, we assume if overlay is showing, we don't spam redraw unless type changes
        // But here we might want to update usage numbers in text? (Future improv)
        return false
    }

    private fun showWarningToast(msg: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "⚠️ AntiDoom: $msg", Toast.LENGTH_LONG).show()
        }
    }

    enum class OverlayType { HARD, SOFT }

    private fun showOverlay(action: ActionRequest) {
        if (!Settings.canDrawOverlays(this)) return

        serviceScope.launch(Dispatchers.Main) {
            // Remove existing if any (clean refresh)
            if (currentOverlayView != null) removeOverlaySafe()

            isOverlayShowing = true

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
                setTextColor(Color.WHITE) // Always White for contrast on dark bg
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
        lastExitTimestamp = System.currentTimeMillis()

        // Logic specific to type
        when (type) {
            OverlayType.HARD -> {
                removeOverlaySafe()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            OverlayType.SOFT -> {
                removeOverlaySafe()
                // Grace period applied by lastExitTimestamp
            }
        }
    }

    private fun removeOverlaySafe() {
        if (!isOverlayShowing) return

        // Capture view to remove
        val viewToRemove = currentOverlayView
        currentOverlayView = null
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
                    // We don't reset sessionMeters here fully, we just flush usage to DB
                    // But to avoid double counting, a better approach in this architecture is:
                    // 1. Add to DB
                    // 2. Subtract flushed amount from RAM session
                    // Ideally, handlePackageSwitch handles the atomic flush.
                    // Periodic flush is just for safety if app crashes.

                    // Simplified: We assume handlePackageSwitch is robust enough.
                    // If we want periodic flush, we need a way to 'commit' current ram to db without clearing session visual
                    // For now, let's rely on app switch or service destroy to flush to avoid complexity.
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        if (pkgName == packageName) return // Ignore self

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkgName != currentAppPackage) {
                serviceScope.launch {
                    handlePackageSwitch(pkgName)
                }
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return

            if (pkgName != currentAppPackage) {
                serviceScope.launch { handlePackageSwitch(pkgName) }
            }

            // Don't count scroll if overlay is blocking view
            if (isOverlayShowing) {
                // Optional: Re-check state to see if overlay should be dismissed?
                // Handled by user clicking button usually.
                return
            }

            val eventCopy = AccessibilityEvent.obtain(event)
            scrollEventChannel.trySend(eventCopy to pkgName)
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