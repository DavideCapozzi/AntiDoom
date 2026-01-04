package com.antidoom.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.rememberAsyncImagePainter
import com.antidoom.app.data.ScrollRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

// Navigation Routes
sealed class Screen(val route: String, val label: String? = null, val icon: ImageVector? = null) {
    data object Stats : Screen("stats", "Stats", Icons.Filled.AutoGraph)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object SettingsLimits : Screen("settings/limits")
    data object SettingsApps : Screen("settings/apps")
    data object SettingsAccessibility : Screen("settings/accessibility")
}

// Shared Data Model
data class AppInfo(
    val label: String,
    val packageName: String
)

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestNotificationPermission()
        setContent {
            AntiDoomApp()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun AntiDoomApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomNavItems = listOf(Screen.Stats, Screen.Settings)
    val showBottomBar = bottomNavItems.any { it.route == currentDestination?.route }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon!!, contentDescription = screen.label) },
                                label = { Text(screen.label!!) },
                                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                                onClick = {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Stats.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Stats.route) { StatsScreen() }
                composable(Screen.Settings.route) { SettingsScreen(navController) }
                composable(Screen.SettingsLimits.route) { LimitSettingsScreen(navController) }
                composable(Screen.SettingsApps.route) { AppsSettingsScreen(navController) }
                composable(Screen.SettingsAccessibility.route) { AccessibilitySettingsScreen(navController) }
            }
        }
    }
}

@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentDate by remember { mutableStateOf(LocalDate.now().toString()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                currentDate = LocalDate.now().toString()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Optimization: remember repository instance
    val repository = remember { ScrollRepository.get(context) }

    val todayDistance by key(currentDate) {
        repository.getDailyDistanceFlow(currentDate).collectAsState(initial = 0f)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AntiDoom Tracker", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.2f m", todayDistance),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text("Scrolled Today", style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsItem(
                title = "Limits Settings",
                subtitle = "Set your daily scrolling budget",
                icon = Icons.Filled.Speed,
                onClick = { navController.navigate(Screen.SettingsLimits.route) }
            )
            SettingsItem(
                title = "Apps Settings",
                subtitle = "Choose apps to track",
                icon = Icons.AutoMirrored.Filled.List,
                onClick = { navController.navigate(Screen.SettingsApps.route) }
            )
            SettingsItem(
                title = "Accessibility Settings",
                subtitle = "Permissions and Overlay",
                icon = Icons.Filled.Accessibility,
                onClick = { navController.navigate(Screen.SettingsAccessibility.route) }
            )
        }
    }
}

@Composable
fun SettingsItem(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessibilitySettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isAccessibilityEnabled by remember { mutableStateOf(checkAccessibilityService(context)) }
    var isOverlayEnabled by remember { mutableStateOf(checkOverlayPermission(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = checkAccessibilityService(context)
                isOverlayEnabled = checkOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility Setup") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { p ->
        Column(modifier = Modifier.padding(p).padding(24.dp)) {
            SetupButton(
                text = if (isAccessibilityEnabled) "✅ Accessibility Active" else "Enable Accessibility",
                isEnabled = !isAccessibilityEnabled,
                onClick = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            SetupButton(
                text = if (isOverlayEnabled) "✅ Overlay Active" else "Enable Overlay Permission",
                isEnabled = !isOverlayEnabled,
                onClick = { openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
            )

            if (isAccessibilityEnabled && isOverlayEnabled) {
                Spacer(modifier = Modifier.height(32.dp))
                Text("All systems nominal.", color = Color.Gray)
            }
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
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(text)
    }
}

// --- OPTIMIZED ICON LOADER ---
@Composable
fun PackageIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Asynchronously load the icon to prevent Main Thread blocking
    val iconState = produceState<Drawable?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName)
            } catch (e: Exception) {
                ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)
            }
        }
    }

    if (iconState.value != null) {
        Image(
            painter = rememberAsyncImagePainter(iconState.value),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        // Placeholder to maintain layout stability
        Box(modifier = modifier)
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