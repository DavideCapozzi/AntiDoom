package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
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
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.antidoom.app.R
import com.antidoom.app.data.ScrollRepository
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@Suppress("AccessibilityServiceInfo")
class ScrollTrackingService : AccessibilityService() {

    companion object {
        private const val PIXELS_PER_METER = 3500f
        private const val LIMIT_METERS_FOR_INTERVENTION = 500f
        private const val FALLBACK_SCROLL_PIXELS = 1500
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var repository: ScrollRepository
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager

    @Volatile
    private var trackedAppsCache: Set<String> = emptySet()

    private var currentPackageName: String = ""
    private var sessionAccumulatedMeters = 0f
    private var interventionAccumulatedMeters = 0f

    @Volatile
    private var isOverlayShowing = false
    private var currentOverlayView: View? = null

    private val scrollEventChannel = Channel<Pair<AccessibilityEvent, String>>(
        capacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = {
            // Channel cleanup if needed
        }
    )

    override fun onCreate() {
        super.onCreate()
        try {
            repository = ScrollRepository.get(applicationContext)
            prefs = UserPreferences(applicationContext)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            startEventConsumer()
            startPeriodicFlushing()

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

    private fun startPeriodicFlushing() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                flushCurrentSession()
            }
        }
    }

    private suspend fun flushCurrentSession() {
        if (sessionAccumulatedMeters > 0 && currentPackageName.isNotEmpty()) {
            val metersToSave = sessionAccumulatedMeters
            sessionAccumulatedMeters = 0f

            repository.flushSessionToDb(currentPackageName, metersToSave)
        }
    }

    private suspend fun processScrollEvent(event: AccessibilityEvent, pkgName: String) {
        currentPackageName = pkgName

        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else FALLBACK_SCROLL_PIXELS
        val meters = pixelsToAdd / PIXELS_PER_METER

        sessionAccumulatedMeters += meters
        interventionAccumulatedMeters += meters

        repository.updateActiveDistance(sessionAccumulatedMeters)

        if (interventionAccumulatedMeters >= LIMIT_METERS_FOR_INTERVENTION) {
            interventionAccumulatedMeters = 0f
            triggerIntervention()
        }
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        val delta = abs(event.scrollDeltaY)
        return if (delta > 0) delta else 0
    }

    private fun startForegroundServiceSafe() {
        val channelId = "antidoom_service_channel"
        val manager = getSystemService(NotificationManager::class.java)

        if (manager.getNotificationChannel(channelId) == null) {
            val channel = NotificationChannel(
                channelId,
                "AntiDoom Monitor",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background scroll monitoring"
            }
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
                    this,
                    1,
                    notification,
                    if (Build.VERSION.SDK_INT >= 34) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    } else {
                        0
                    }
                )
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            Log.e("AntiDoom", "Failed to start foreground service", e)
        }
    }

    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isOverlayShowing) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName !in trackedAppsCache) return

        val eventCopy = AccessibilityEvent.obtain(event)
        scrollEventChannel.trySend(eventCopy to pkgName)
    }

    @SuppressLint("SetTextI18n")
    private fun triggerIntervention() {
        if (isOverlayShowing) return
        if (!Settings.canDrawOverlays(this)) return

        serviceScope.launch(Dispatchers.Main) {
            isOverlayShowing = true

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )

            val overlayView = FrameLayout(this@ScrollTrackingService).apply {
                setBackgroundColor(Color.parseColor("#D9000000"))

                addView(TextView(context).apply {
                    text = "DOOMSCROLLING DETECTED\n\nTake a breath."
                    setTextColor(Color.WHITE)
                    textSize = 28f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }

            try {
                windowManager.addView(overlayView, params)
                currentOverlayView = overlayView
                delay(10_000)
                removeOverlaySafe()
            } catch (e: Exception) {
                Log.e("AntiDoom", "Overlay failed", e)
                removeOverlaySafe()
            }
        }
    }

    private fun removeOverlaySafe() {
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

    override fun onInterrupt() { }

    override fun onDestroy() {
        if (sessionAccumulatedMeters > 0) {
            runBlocking {
                try {
                    repository.flushSessionToDb(currentPackageName, sessionAccumulatedMeters)
                } catch (_: Exception) { }
            }
        }
        removeOverlaySafe()
        serviceScope.cancel()
        scrollEventChannel.close()
        super.onDestroy()
    }
}