package com.antidoom.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.antidoom.app.data.ScrollRepository
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestNotificationPermission()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityService(context)) }
    var isOverlayEnabled by remember { mutableStateOf(checkOverlayPermission(context)) }
    var currentDate by remember { mutableStateOf(LocalDate.now().toString()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityService(context)
                isOverlayEnabled = checkOverlayPermission(context)
                currentDate = LocalDate.now().toString()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val repository = remember { ScrollRepository.get(context) }

    val todayDistance by key(currentDate) {
        repository.getDailyDistanceFlow(currentDate)
            .collectAsState(initial = 0f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AntiDoom Tracker",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = String.format(java.util.Locale.US, "%.2f m", todayDistance),
            style = MaterialTheme.typography.displayLarge.copy(
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = "Scrolled Today",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(64.dp))

        SetupButton(
            text = if (isAccessibilityEnabled) "✅ Accessibility Active" else "1. Enable Accessibility",
            isEnabled = !isAccessibilityEnabled,
            onClick = {
                openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        SetupButton(
            text = if (isOverlayEnabled) "✅ Overlay Active" else "2. Enable Overlay Permission",
            isEnabled = !isOverlayEnabled,
            onClick = {
                openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
        )

        if (isAccessibilityEnabled && isOverlayEnabled) {
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "Tracking is active in background",
                style = MaterialTheme.typography.labelMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
fun SetupButton(text: String, isEnabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = isEnabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primary else Color.Green.copy(alpha = 0.6f),
            disabledContainerColor = Color.Gray.copy(alpha = 0.2f),
            disabledContentColor = Color.DarkGray
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(text)
    }
}

fun checkAccessibilityService(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    return enabledServices.any { it.resolveInfo.serviceInfo.packageName == context.packageName }
}

fun checkOverlayPermission(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

fun openSettings(context: Context, action: String) {
    try {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (action == Settings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                data = android.net.Uri.parse("package:${context.packageName}")
            }
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}