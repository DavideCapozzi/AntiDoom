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
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    // Caches for Limits
    private var appLimitsCache: Map<String, Float> = emptyMap()
    private var dailyAppUsageCache: Map<String, Float> = emptyMap() // From DB

    // Config Cache
    @Volatile private var trackedAppsCache: Set<String> = emptySet()

    // Global Counters
    @Volatile private var currentDailyTotal: Float = 0f // From DB + RAM handled in check

    // Dynamic Limits
    @Volatile private var limitHardMeters: Float = 100f
    private var limitSoftMeters: Float = 50f
    private var limitWarningMeters: Float = 25f

    // Current Session State
    @Volatile private var currentAppPackage: String = ""
    private var sessionAccumulatedMeters = 0f

    // Intervention State
    private var lastExitTimestamp: Long = 0L
    @Volatile private var isOverlayShowing = false
    private var currentOverlayView: View? = null
    private var hasWarned25 = false
    private var hasSoftBlocked50 = false
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

            startForegroundServiceSafe()
        } catch (e: Exception) {
            Log.e("AntiDoom", "Service initialization failed", e)
        }
    }

    private fun startStateObservation() {
        // Observe Limits & Tracked Apps Configuration
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
                updateLimits(globalLimit)
            }
        }

        // Observe Daily Usage from DB
        serviceScope.launch {
            while (isActive) {
                val today = LocalDate.now().toString()

                if (today != lastCheckDate) {
                    hasWarned25 = false
                    hasSoftBlocked50 = false
                    lastCheckDate = today
                }

                repository.getDailyAppDistancesFlow(today)
                    .distinctUntilChanged()
                    .collectLatest { map ->
                        dailyAppUsageCache = map
                        currentDailyTotal = map.values.sum()
                        // Re-check enforcement whenever DB updates
                        checkEnforcementState()
                    }
            }
        }
    }

    private fun updateLimits(limit: Float) {
        limitHardMeters = limit
        limitSoftMeters = limit * 0.5f
        limitWarningMeters = limit * 0.25f
        checkEnforcementState()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        serviceInfo = info
    }

    private fun startEventConsumer() {
        serviceScope.launch {
            for ((event, pkgName) in scrollEventChannel) {
                try {
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
        // Double check package switch in consumer thread
        if (currentAppPackage != pkgName) {
            handlePackageSwitch(pkgName)
        }

        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else FALLBACK_SCROLL_PIXELS
        val meters = pixelsToAdd / PIXELS_PER_METER

        sessionAccumulatedMeters += meters
        repository.updateActiveDistance(sessionAccumulatedMeters) // Update RAM view

        // Check limits after adding meters
        checkEnforcementState()
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    /**
     * CRITICAL FIX: Safe Package Switching
     * Flushes the previous app's session BEFORE switching currentAppPackage variable.
     */
    private suspend fun handlePackageSwitch(newPackage: String) {
        if (currentAppPackage.isNotEmpty() && sessionAccumulatedMeters > 0) {
            // Save meters to the OLD package
            repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
            sessionAccumulatedMeters = 0f
            repository.updateActiveDistance(0f)
        }
        currentAppPackage = newPackage
    }

    private fun checkEnforcementState() {
        if (System.currentTimeMillis() - lastExitTimestamp < EXIT_GRACE_PERIOD_MS) {
            return
        }

        // Safety check: if current app is not tracked, ensure overlay is gone and return
        if (currentAppPackage !in trackedAppsCache) {
            removeOverlaySafe()
            return
        }

        // Calculate usage considering DB + Current RAM Session
        var effectiveAppUsage = (dailyAppUsageCache[currentAppPackage] ?: 0f)
        var effectiveTotalUsage = currentDailyTotal

        // Add current session only if it matches (it should, thanks to handlePackageSwitch)
        if (currentAppPackage.isNotEmpty()) {
            effectiveAppUsage += sessionAccumulatedMeters
            effectiveTotalUsage += sessionAccumulatedMeters
        }

        // 1. CHECK GLOBAL LIMIT
        if (effectiveTotalUsage >= limitHardMeters) {
            if (!isOverlayShowing) {
                showOverlay(
                    type = OverlayType.HARD,
                    title = "⛔ LIMIT REACHED",
                    message = "Global budget exhausted (${limitHardMeters.toInt()}m).",
                    btnText = "GO TO HOME SCREEN",
                    bgColor = Color.parseColor("#F2000000"),
                    titleColor = Color.RED
                )
            }
            return
        }

        // 2. CHECK SPECIFIC APP LIMIT
        val specificLimit = appLimitsCache[currentAppPackage]
        if (specificLimit != null && effectiveAppUsage >= specificLimit) {
            if (!isOverlayShowing) {
                showOverlay(
                    type = OverlayType.HARD,
                    title = "⛔ APP LIMIT REACHED",
                    message = "Limit for this app reached (${specificLimit.toInt()}m).",
                    btnText = "CLOSE APP",
                    bgColor = Color.parseColor("#F2000000"),
                    titleColor = Color.RED
                )
            }
            return
        }

        // 3. SOFT BLOCK (50% of Global)
        if (effectiveTotalUsage >= limitSoftMeters && !hasSoftBlocked50) {
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

        // Remove overlay if limits are satisfied
        if (effectiveTotalUsage < limitHardMeters &&
            (specificLimit == null || effectiveAppUsage < specificLimit) &&
            effectiveTotalUsage < limitSoftMeters && isOverlayShowing) {
            removeOverlaySafe()
        }

        // 4. WARNING TOAST (25% of Global)
        if (effectiveTotalUsage >= limitWarningMeters &&
            !hasWarned25 &&
            effectiveTotalUsage < limitSoftMeters) {
            hasWarned25 = true
            showWarningToast()
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
                isClickable = true // Capture clicks

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
        lastExitTimestamp = System.currentTimeMillis()
        when (type) {
            OverlayType.HARD -> {
                // For hard block, reset current package to avoid immediate re-trigger
                // But perform global home action
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
                if (sessionAccumulatedMeters > 0 && currentAppPackage.isNotEmpty()) {
                    repository.flushSessionToDb(currentAppPackage, sessionAccumulatedMeters)
                    sessionAccumulatedMeters = 0f
                    repository.updateActiveDistance(0f)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        if (pkgName == packageName) return // Ignore self

        // 1. WINDOW STATE CHANGED (App Switch)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkgName != currentAppPackage) {
                // Must launch in serviceScope to allow suspend function (DB write)
                serviceScope.launch {
                    handlePackageSwitch(pkgName)
                    checkEnforcementState()
                }
            }
            return
        }

        // 2. VIEW SCROLLED (Tracking)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            if (pkgName !in trackedAppsCache) return

            // If we missed a window change, handle switch now
            if (pkgName != currentAppPackage) {
                serviceScope.launch { handlePackageSwitch(pkgName) }
            }

            if (isOverlayShowing) {
                checkEnforcementState()
                return
            }

            // Send to processing channel
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