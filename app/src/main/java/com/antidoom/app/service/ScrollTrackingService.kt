package com.antidoom.app.service

import android.accessibilityservice.AccessibilityService
import android.app.*
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
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.abs

class ScrollTrackingService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var prefs: UserPreferences
    
    // Tracking Vars
    private var currentSessionDistance = 0f
    private var lastScrollY = 0
    private var isOverlayShowing = false
    private val PIXELS_PER_METER = 3500f // Taratura approssimativa
    private val LIMIT_METERS = 500f // Trigger Intervention

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(applicationContext)
        prefs = UserPreferences(applicationContext)
        startForegroundSafe()
    }

    private fun startForegroundSafe() {
        val channelId = "antidoom_service"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Tracking", NotificationManager.IMPORTANCE_LOW))

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AntiDoom Active")
            .setSmallIcon(R.mipmap.ic_launcher) // Assicurati di avere un'icona
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isOverlayShowing) return

        scope.launch {
            try {
                val pkgName = event.packageName?.toString() ?: return@launch
                val tracked = prefs.trackedApps.first()
                
                if (pkgName in tracked && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                    processScroll(event)
                }
            } catch (e: Exception) {
                Log.e("AntiDoom", "Error processing event", e)
            }
        }
    }

    private suspend fun processScroll(event: AccessibilityEvent) {
        val source = event.source ?: return
        // Tentativo di calcolo delta
        val currentY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
             // API 28+ è più preciso
             if (event.scrollDeltaY != 0) {
                 accumulate(abs(event.scrollDeltaY))
                 return
             }
             0 // Fallback
        } else 0

        // Fallback per vecchie API o se deltaY è 0
        // Nota: questo è un'approssimazione basata sul cambio di indice o scrollY della view
        // Per semplicità usiamo un valore fisso se rileviamo lo scroll, 
        // in produzione servirebbe logica complessa per ogni UI (RecyclerView vs ListView)
        accumulate(200) // Assumiamo 200px per ogni evento scroll "vuoto"
        source.recycle()
    }

    private suspend fun accumulate(pixels: Int) {
        val meters = pixels / PIXELS_PER_METER
        currentSessionDistance += meters
        
        Log.d("AntiDoom", "Distance: $currentSessionDistance m")

        if (currentSessionDistance >= LIMIT_METERS) {
            withContext(Dispatchers.Main) { showPunishmentOverlay() }
        }
        
        // Salva periodicamente (ogni 10m o simili) - qui semplificato
        if (currentSessionDistance % 10 < 0.5) {
            saveToDb()
        }
    }
    
    private suspend fun saveToDb() {
        val today = LocalDate.now().toString()
        db.scrollDao().insert(ScrollSession(
            packageName = "tracked.app",
            distanceMeters = currentSessionDistance,
            timestamp = System.currentTimeMillis(),
            date = today
        ))
    }

    private suspend fun showPunishmentOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        isOverlayShowing = true
        
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        // UI Overlay semplice ma efficace (non Compose per evitare crash in Service puro)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#EE000000")) // Nero semitrasparente
            addView(TextView(context).apply {
                text = "DOOMSCROLLING DETECTED\nTake a breath."
                setTextColor(Color.WHITE)
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            })
        }

        try {
            wm.addView(overlay, params)
            // Blocca per 10 secondi
            delay(10_000)
            wm.removeView(overlay)
            
            // Reset
            currentSessionDistance = 0f
            isOverlayShowing = false
        } catch (e: Exception) {
            isOverlayShowing = false
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
