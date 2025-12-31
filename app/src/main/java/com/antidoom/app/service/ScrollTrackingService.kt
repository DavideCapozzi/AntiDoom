package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
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
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.antidoom.app.R
import com.antidoom.app.data.AppDatabase
import com.antidoom.app.data.ScrollSession
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.*
import java.time.LocalDate
import kotlin.math.abs

class ScrollTrackingService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPreferences
    
    // Cache for tracked apps to avoid IO operations on the UI/Accessibility thread
    @Volatile
    private var trackedAppsCache: Set<String> = emptySet()

    // Tracking Variables
    private var currentSessionDistance = 0f
    private var isOverlayShowing = false
    private val PIXELS_PER_METER = 3500f // Approximate calibration
    private val LIMIT_METERS = 500f // Trigger Intervention

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(applicationContext)
        prefs = UserPreferences(applicationContext)
        
        // Start monitoring preferences in background.
        // This updates the cache whenever the user changes settings, 
        // allowing instant access in onAccessibilityEvent without database locking.
        scope.launch {
            prefs.trackedApps.collect { apps ->
                trackedAppsCache = apps
                Log.d("AntiDoom", "Tracked apps cache updated: $apps")
            }
        }
        
        startForegroundSafe()
    }

    private fun startForegroundSafe() {
        val channelId = "antidoom_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Tracking Service", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AntiDoom Active")
            .setContentText("Monitoring scroll behavior")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        // Ensure compatibility with Android 14 foreground service types
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isOverlayShowing) return

        // CRITICAL PERFORMANCE FIX:
        // Do not launch a coroutine or do any heavy lifting if the event is not relevant.
        // 1. Filter by Event Type (ignore window changes, focus changes, etc.)
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        // 2. Filter by Package Name using the in-memory cache (zero IO latency)
        val pkgName = event.packageName?.toString()
        if (pkgName == null || pkgName !in trackedAppsCache) return

        // If we passed the filters, process the scroll in a background thread
        scope.launch {
            processScroll(event)
        }
    }

    private suspend fun processScroll(event: AccessibilityEvent) {
        // Attempt to calculate precise delta
        val deltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
             // API 28+ precise delta
             if (event.scrollDeltaY != 0) {
                 abs(event.scrollDeltaY)
             } else {
                 0
             }
        } else {
            0
        }

        // Logic: If delta is 0 but we received a SCROLL event, use a fallback heuristic.
        // This often happens in webviews or custom views that don't report delta.
        val pixelsToAdd = if (deltaY > 0) deltaY else 200

        accumulate(pixelsToAdd)
        
        // Don't forget to recycle if you obtained a copy, 
        // but here 'event' is passed by system, usually safe to just read.
    }

    private suspend fun accumulate(pixels: Int) {
        val meters = pixels / PIXELS_PER_METER
        currentSessionDistance += meters
        
        // Log sparingly in production to avoid logcat spam
        // Log.d("AntiDoom", "Distance: $currentSessionDistance m")

        if (currentSessionDistance >= LIMIT_METERS) {
            withContext(Dispatchers.Main) { showPunishmentOverlay() }
        }
        
        // Save periodically (Debouncing could be improved here, but this is functional)
        if (currentSessionDistance % 10 < 0.5) {
            saveToDb()
        }
    }
    
    private suspend fun saveToDb() {
        val today = LocalDate.now().toString()
        db.scrollDao().insert(ScrollSession(
            packageName = "tracked.app", // Consider passing actual package name
            distanceMeters = currentSessionDistance,
            timestamp = System.currentTimeMillis(),
            date = today
        ))
    }

    private suspend fun showPunishmentOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        isOverlayShowing = true
        
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Use TYPE_APPLICATION_OVERLAY for Android O+
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

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#EE000000")) // Semi-transparent black
            addView(TextView(context).apply {
                text = "DOOMSCROLLING DETECTED\nTake a breath."
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, 
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
        }

        try {
            wm.addView(overlay, params)
            // Block usage for 10 seconds
            delay(10_000)
            wm.removeView(overlay)
            
            // Reset counters
            currentSessionDistance = 0f
            isOverlayShowing = false
        } catch (e: Exception) {
            Log.e("AntiDoom", "Overlay error", e)
            isOverlayShowing = false
        }
    }

    override fun onInterrupt() {
        // Service interrupted by system
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
