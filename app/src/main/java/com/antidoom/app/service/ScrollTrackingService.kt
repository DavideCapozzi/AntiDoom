package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.LocalDate
import kotlin.math.abs

@Suppress("AccessibilityServiceInfo")
class ScrollTrackingService : AccessibilityService() {

    companion object {
        private const val PIXELS_PER_METER = 3500f
        private const val FALLBACK_SCROLL_PIXELS = 1500

        // --- INTERVENTION THRESHOLDS ---
        private const val LIMIT_HARD_METERS = 100f   // 100% - Stop
        private const val LIMIT_SOFT_METERS = 50f    // 50%  - Pause/Intervene
        private const val LIMIT_WARNING_METERS = 25f // 25%  - Toast Warning

        // Grace period is still useful for UX, but NOT for logic correctness
        private const val EXIT_GRACE_PERIOD_MS = 2000L
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    // State Tracking
    @Volatile private var trackedAppsCache: Set<String> = emptySet()
    @Volatile private var currentDailyTotal: Float = 0f

    // Tracks the last external package
    @Volatile private var currentAppPackage: String = ""

    // Timestamp of the last forced exit
    private var lastExitTimestamp: Long = 0L

    // Accumulators
    private var sessionAccumulatedMeters = 0f

    // UI State
    @Volatile private var isOverlayShowing = false
    private var currentOverlayView: View? = null

    // Intervention Flags (Reset daily)
    private var hasWarned25 = false
    private var hasSoftBlocked50 = false

    // Date for daily reset
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

            startEventConsumer()
            startPeriodicFlushing()
            startStateObservation()

            serviceScope.launch {
                prefs.trackedApps.collectLatest { apps ->
                    trackedAppsCache = apps
                }
            }

            startForegroundServiceSafe()
        } catch (e: Exception) {
            Log.e("AntiDoom", "Service initialization failed", e)
        }
    }

    private fun startStateObservation() {
        serviceScope.launch {
            while (isActive) {
                val today = LocalDate.now().toString()

                if (today != lastCheckDate) {
                    hasWarned25 = false
                    hasSoftBlocked50 = false
                    lastCheckDate = today
                }

                repository.getDailyDistanceFlow(today)
                    .distinctUntilChanged()
                    .collectLatest { total ->
                        currentDailyTotal = total
                        // Trigger a check whenever data changes
                        checkEnforcementState()
                    }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        // We need retrieve window content to verify the active window
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, _) in scrollEventChannel) {
                try {
                    processScrollEvent(event)
                } catch (e: Exception) {
                    Log.e("AntiDoom", "Error processing scroll event", e)
                }
            }
        }
    }

    private suspend fun processScrollEvent(event: AccessibilityEvent) {
        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else FALLBACK_SCROLL_PIXELS
        val meters = pixelsToAdd / PIXELS_PER_METER

        sessionAccumulatedMeters += meters
        repository.updateActiveDistance(sessionAccumulatedMeters)
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    /**
     * CORE LOGIC: Validates state and decides enforcement.
     * Includes "Sanity Check" to prevent Ghost Overlays.
     */
    private fun checkEnforcementState() {
        // 1. TIMING GUARD: If we recently exited, pause checks to allow animation/transition
        if (System.currentTimeMillis() - lastExitTimestamp < EXIT_GRACE_PERIOD_MS) {
            return
        }

        // 2. SANITY CHECK (The Fix):
        // Before relying on `currentAppPackage`, ask the system what is REALLY top-most.
        // This handles cases where event stream lags behind reality (Zombie events).
        val actualForeground = activeWindowPackageName

        // If we can determine the actual foreground, and it contradicts our cached state, update it.
        if (actualForeground != null && actualForeground != currentAppPackage) {
            // Special case: If our overlay is the top window, ignore this check
            // (otherwise we might detect ourselves and think we aren't in the app).
            if (actualForeground != packageName) {
                currentAppPackage = actualForeground
            }
        }

        // 3. TRACKING CHECK: If not in a tracked app, clean up and exit.
        if (currentAppPackage !in trackedAppsCache) {
            removeOverlaySafe()
            return
        }

        // 4. HARD BLOCK (100% Limit)
        if (currentDailyTotal >= LIMIT_HARD_METERS) {
            if (!isOverlayShowing) {
                showOverlay(
                    type = OverlayType.HARD,
                    title = "⛔ LIMIT REACHED",
                    message = "You've exhausted your scroll budget for today.\nTake a breath.",
                    btnText = "GO TO HOME SCREEN",
                    bgColor = Color.parseColor("#F2000000"),
                    titleColor = Color.RED
                )
            }
            return
        }

        // 5. SOFT BLOCK (50% Limit)
        if (currentDailyTotal >= LIMIT_SOFT_METERS && !hasSoftBlocked50) {
            if (!isOverlayShowing) {
                hasSoftBlocked50 = true
                showOverlay(
                    type = OverlayType.SOFT,
                    title = "⚠️ HALFWAY THERE",
                    message = "You have reached 50% of your daily limit.\nDo you want to continue?",
                    btnText = "I want to continue scrolling",
                    bgColor = Color.parseColor("#F2CC5500"),
                    titleColor = Color.WHITE
                )
            }
            return
        }

        // Cleanup if limits are no longer met
        if (currentDailyTotal < LIMIT_SOFT_METERS && isOverlayShowing) {
            removeOverlaySafe()
        }

        // 6. WARNING TOAST
        if (currentDailyTotal >= LIMIT_WARNING_METERS &&
            !hasWarned25 &&
            currentDailyTotal < LIMIT_SOFT_METERS) {
            hasWarned25 = true
            showWarningToast()
        }
    }

    /**
     * Helper to get the definitive package name from the active window.
     * This bypasses the event stream lag.
     */
    private val activeWindowPackageName: String?
        get() {
            return try {
                // Method 1: rootInActiveWindow (Most reliable for "what user is interacting with")
                val root = rootInActiveWindow
                if (root != null) {
                    val pkg = root.packageName?.toString()
                    // Don't recycle root here if we just access property,
                    // but standard practice suggests being careful.
                    // However, rootInActiveWindow does not need explicit recycle in recent APIs
                    // unless we iterate children, but let's be safe if we were traversing.
                    // For just packageName, it's fine.
                    return pkg
                }
                null
            } catch (e: Exception) {
                null
            }
        }

    private fun showWarningToast() {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "⚠️ AntiDoom: 25% used.", Toast.LENGTH_LONG).show()
        }
    }

    enum class OverlayType { HARD, SOFT }

    @SuppressLint("SetTextI18n")
    private fun showOverlay(
        type: OverlayType,
        title: String,
        message: String,
        btnText: String,
        bgColor: Int,
        titleColor: Int
    ) {
        if (isOverlayShowing) return
        if (!Settings.canDrawOverlays(this)) return

        serviceScope.launch(Dispatchers.Main) {
            if (isOverlayShowing) return@launch
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

            val overlayView = FrameLayout(this@ScrollTrackingService).apply {
                setBackgroundColor(bgColor)
                // ... (UI Code remains identical to original for brevity) ...
                // Re-inserting the exact UI setup code here:
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
                    text = title
                    setTextColor(titleColor)
                    textSize = 28f
                    gravity = Gravity.CENTER
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }

                val messageView = TextView(context).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding(32, 32, 32, 64)
                }

                val actionButton = Button(context).apply {
                    text = btnText
                    setBackgroundColor(Color.WHITE)
                    setTextColor(Color.BLACK)
                    setOnClickListener { handleOverlayAction(type) }
                }

                container.addView(titleView)
                container.addView(messageView)
                container.addView(actionButton)
                addView(container)
            }

            try {
                windowManager.addView(overlayView, params)
                currentOverlayView = overlayView
            } catch (e: Exception) {
                Log.e("AntiDoom", "Overlay failed", e)
                isOverlayShowing = false
            }
        }
    }

    private fun handleOverlayAction(type: OverlayType) {
        // Prevent re-triggering immediately
        lastExitTimestamp = System.currentTimeMillis()

        when (type) {
            OverlayType.HARD -> {
                // Clear package to stop tracking immediately
                currentAppPackage = ""
                removeOverlaySafe()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            OverlayType.SOFT -> {
                removeOverlaySafe()
            }
        }
    }

    private fun removeOverlaySafe() {
        if (!isOverlayShowing) return

        serviceScope.launch(Dispatchers.Main) {
            try {
                currentOverlayView?.let { view ->
                    if (view.isAttachedToWindow) {
                        windowManager.removeView(view)
                    }
                }
            } catch (e: Exception) {
                Log.e("AntiDoom", "Error removing overlay", e)
            } finally {
                currentOverlayView = null
                isOverlayShowing = false
            }
        }
    }

    private fun startPeriodicFlushing() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                flushCurrentSession()
            }
        }
    }

    private suspend fun flushCurrentSession() {
        if (sessionAccumulatedMeters > 0 && currentAppPackage.isNotEmpty()) {
            val metersToSave = sessionAccumulatedMeters
            sessionAccumulatedMeters = 0f
            repository.flushSessionToDb(currentAppPackage, metersToSave)
        }
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        // 1. SELF FILTER: Ignore events from our own overlay
        if (pkgName == packageName) return

        // 2. STATE CHANGE HANDLING
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            // If we are in the grace period, we trust the "Go Home" action
            // and ignore temporary flickers from the closing app.
            if (System.currentTimeMillis() - lastExitTimestamp < EXIT_GRACE_PERIOD_MS) {
                // OPTIONAL: We could double check here if pkgName is NOT a tracked app
                // (e.g. Launcher), then we could accept it.
                // But safer to just wait for the grace period or let checkEnforcementState
                // handle the "Sanity Check".
                return
            }

            // Update local state, but rely on checkEnforcementState to validate it
            currentAppPackage = pkgName
            checkEnforcementState()
            return
        }

        // 3. SCROLL HANDLING
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return

            if (isOverlayShowing) {
                // If overlay is up, ensure it should stay up
                checkEnforcementState()
                return
            }

            val eventCopy = AccessibilityEvent.obtain(event)
            scrollEventChannel.trySend(eventCopy to pkgName)
        }
    }

    private fun startForegroundServiceSafe() {
        // ... (Same as original) ...
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