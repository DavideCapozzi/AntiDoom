package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
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
import com.antidoom.app.data.AppDatabase
import com.antidoom.app.data.ScrollSession
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import java.time.LocalDate
import kotlin.math.abs

class ScrollTrackingService : AccessibilityService() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPreferences
    private lateinit var windowManager: WindowManager
    
    @Volatile
    private var trackedAppsCache: Set<String> = emptySet()

    private var currentSessionDistance = 0f
    private var lastSavedDistance = 0f
    
    private var isOverlayShowing = false
    private var currentOverlayView: View? = null

    private val scrollEventChannel = Channel<Pair<AccessibilityEvent, String>>(
        capacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val PIXELS_PER_METER = 3500f 
    private val LIMIT_METERS = 500f
    private val SAVE_THRESHOLD_METERS = 100f 

    override fun onCreate() {
        super.onCreate()
        try {
            db = AppDatabase.get(applicationContext)
            prefs = UserPreferences(applicationContext)
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            
            startEventConsumer()

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
                } finally {
                    try { event.recycle() } catch (e: Exception) { }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isOverlayShowing) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val pkgName = event.packageName?.toString() ?: return
        if (pkgName !in trackedAppsCache) return

        val eventCopy = AccessibilityEvent.obtain(event)
        val sent = scrollEventChannel.trySend(eventCopy to pkgName).isSuccess
        if (!sent) {
            eventCopy.recycle()
        }
    }

    private suspend fun processScrollEvent(event: AccessibilityEvent, pkgName: String) {
        val deltaY = getScrollDelta(event)
        val pixelsToAdd = if (deltaY > 0) deltaY else 250 
        
        accumulateDistance(pixelsToAdd, pkgName)
    }

    private fun getScrollDelta(event: AccessibilityEvent): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val delta = abs(event.scrollDeltaY)
            if (delta > 0) delta else 0
        } else {
            0
        }
    }

    private suspend fun accumulateDistance(pixels: Int, pkgName: String) {
        val meters = pixels / PIXELS_PER_METER
        currentSessionDistance += meters

        if (currentSessionDistance >= LIMIT_METERS) {
            withContext(Dispatchers.Main) {
                triggerIntervention()
            }
        }

        if (currentSessionDistance - lastSavedDistance >= SAVE_THRESHOLD_METERS) {
            val distanceToSave = currentSessionDistance - lastSavedDistance
            if (saveProgress(pkgName, distanceToSave)) {
                lastSavedDistance = currentSessionDistance
            }
        }
    }

    private suspend fun saveProgress(pkgName: String, distance: Float): Boolean {
        return try {
            val session = ScrollSession(
                packageName = pkgName,
                distanceMeters = distance,
                timestamp = System.currentTimeMillis(),
                date = LocalDate.now().toString()
            )
            db.scrollDao().insert(session)
            true
        } catch (e: Exception) {
            Log.e("AntiDoom", "Database write failed", e)
            false
        }
    }

    private suspend fun triggerIntervention() {
        if (isOverlayShowing) return
        if (!Settings.canDrawOverlays(this)) return

        isOverlayShowing = true
        
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        val overlayView = FrameLayout(this).apply {
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
            
            currentSessionDistance = 0f
            lastSavedDistance = 0f
            
        } catch (e: Exception) {
            Log.e("AntiDoom", "Overlay failed", e)
            removeOverlaySafe()
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
        removeOverlaySafe()
        serviceScope.cancel() 
        scrollEventChannel.close()
        super.onDestroy()
    }
}
