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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.antidoom.app.data.ScrollRepository
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import coil.compose.rememberAsyncImagePainter

// Navigation Routes
sealed class Screen(val route: String, val label: String? = null, val icon: ImageVector? = null) {
    data object Stats : Screen("stats", "Stats", Icons.Filled.AutoGraph)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object SettingsLimits : Screen("settings/limits")
    data object SettingsApps : Screen("settings/apps")
    data object SettingsAccessibility : Screen("settings/accessibility")
}

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
fun LimitSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()
    val currentLimit by prefs.dailyLimit.collectAsState(initial = 100f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limits") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { p ->
        Column(modifier = Modifier.padding(p).padding(24.dp)) {
            Text("Daily Limit: ${currentLimit.toInt()} meters", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
            Slider(
                value = currentLimit,
                onValueChange = { scope.launch { prefs.updateDailyLimit(it) } },
                valueRange = 10f..500f,
                steps = 48
            )
            Text("Adjust the slider to set your goal.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

data class AppInfo(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()

    // Whitelist: Apps in this list ARE tracked
    val trackedApps by prefs.trackedApps.collectAsState(initial = emptySet())

    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    val corePackages = remember {
        setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube"
        )
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(intent, 0)
                .map { resolveInfo ->
                    AppInfo(
                        label = resolveInfo.loadLabel(pm).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                        icon = resolveInfo.loadIcon(pm)
                    )
                }
                .filter { it.packageName != context.packageName } // Never track AntiDoom itself
                .sortedBy { it.label }
                .distinctBy { it.packageName }

            installedApps = apps
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracked Apps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { p ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            // Split apps into Core and Other
            val coreAppsList = installedApps.filter { it.packageName in corePackages }
            val otherAppsList = installedApps.filter { it.packageName !in corePackages }

            LazyColumn(modifier = Modifier.padding(p)) {
                // SECTION 1: CORE APPS
                if (coreAppsList.isNotEmpty()) {
                    item {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Core Doomscrolling Apps",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    items(coreAppsList, key = { it.packageName }) { app ->
                        AppItem(app, app.packageName in trackedApps) { isChecked ->
                            scope.launch {
                                val newSet = if (isChecked) trackedApps + app.packageName else trackedApps - app.packageName
                                prefs.updateTrackedApps(newSet)
                            }
                        }
                    }
                }

                // SECTION 2: OTHER APPS
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Other Apps (Optional)",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                items(otherAppsList, key = { it.packageName }) { app ->
                    AppItem(app, app.packageName in trackedApps) { isChecked ->
                        scope.launch {
                            val newSet = if (isChecked) trackedApps + app.packageName else trackedApps - app.packageName
                            prefs.updateTrackedApps(newSet)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppItem(app: AppInfo, isTracked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(app.label) },
        leadingContent = {
            Image(
                painter = rememberAsyncImagePainter(app.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Checkbox(
                checked = isTracked,
                onCheckedChange = onToggle
            )
        },
        modifier = Modifier.clickable { onToggle(!isTracked) }
    )
    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
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

// Reusing existing helper components
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