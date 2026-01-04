package com.antidoom.app.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.antidoom.app.data.DailyAppSegmentTuple
import com.antidoom.app.data.ScrollRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// NOTE: We do NOT import android.graphics.Canvas here to avoid conflict with Compose Canvas.
// We use fully qualified name 'android.graphics.Canvas' in PackageIcon instead.

sealed class Screen(val route: String, val label: String? = null, val icon: ImageVector? = null) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Stats : Screen("stats", "Stats", Icons.AutoMirrored.Filled.ShowChart)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
    data object SettingsLimits : Screen("settings/limits")
    data object SettingsApps : Screen("settings/apps")
    data object SettingsAccessibility : Screen("settings/accessibility")
}

data class AppInfo(
    val label: String,
    val packageName: String
)

object IconCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    fun get(key: String): Bitmap? = memoryCache.get(key)
    fun put(key: String, bitmap: Bitmap) { memoryCache.put(key, bitmap) }
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

    val bottomNavItems = listOf(Screen.Stats, Screen.Home, Screen.Settings)
    val showBottomBar = bottomNavItems.any { it.route == currentDestination?.route }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        bottomNavItems.forEach { screen ->
                            NavigationBarItem(
                                icon = { Icon(screen.icon!!, contentDescription = screen.label) },
                                label = null,
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
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(Screen.Home.route) { HomeScreen() }
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
fun HomeScreen() {
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
        Text("AntiDoom", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(48.dp))
        Text(
            text = String.format(java.util.Locale.US, "%.2f m", todayDistance),
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text("Scrolled Today", style = MaterialTheme.typography.bodyLarge)
    }
}

// --- STATS & CHART LOGIC ---

data class ChartDayData(
    val dayLabel: String,
    val date: String,
    val segments: List<ChartSegment>,
    val total: Float
)

data class ChartSegment(
    val value: Float,
    val color: Color,
    val label: String? = null
)

val ChartPalette = listOf(
    Color(0xFF3F51B5), // Indigo
    Color(0xFFE91E63), // Pink
    Color(0xFF009688), // Teal
    Color(0xFFFF9800), // Orange
    Color(0xFF9C27B0)  // Purple
)
val ColorOthers = Color.LightGray

@OptIn(ExperimentalTextApi::class)
@Composable
fun StatsScreen() {
    val context = LocalContext.current
    val repository = remember { ScrollRepository.get(context) }

    val rawData by repository.getWeeklyAppBreakdown().collectAsState(initial = emptyList())

    val chartData = remember(rawData) {
        processDataForStackedChart(rawData)
    }

    val legendItems = remember(chartData) {
        chartData.flatMap { it.segments }
            .filter { it.label != null }
            .distinctBy { it.label }
            .sortedByDescending { segment ->
                chartData.sumOf { day -> day.segments.find { it.label == segment.label }?.value?.toDouble() ?: 0.0 }
            }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Weekly Breakdown", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Top 5 Apps + Others", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(24.dp))

        if (chartData.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No data yet", color = Color.Gray)
            }
        } else {
            StackedBarChart(
                data = chartData,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Legend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(legendItems) { segment ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(12.dp).background(segment.color, CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        val cleanName = segment.label?.substringAfterLast('.')
                            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                            ?: "Unknown"
                        Text(cleanName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.size(12.dp).background(ColorOthers, CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Others (Minor Apps)", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                    }
                }
            }
        }
    }
}

fun processDataForStackedChart(raw: List<DailyAppSegmentTuple>): List<ChartDayData> {
    if (raw.isEmpty()) return emptyList()

    val today = LocalDate.now()

    val appTotals = raw.groupBy { it.packageName }
        .mapValues { entry -> entry.value.sumOf { it.total.toDouble() }.toFloat() }
        .toList()
        .sortedByDescending { it.second }

    val topApps = appTotals.take(5).map { it.first }.toSet()

    val colorMap = topApps.mapIndexed { index, pkg ->
        pkg to ChartPalette.getOrElse(index) { Color.Gray }
    }.toMap()

    return (0..6).map { i ->
        val date = today.minusDays(6L - i)
        val dateStr = date.toString()
        val dayLabel = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1)

        val dayEntries = raw.filter { it.date == dateStr }

        val segments = mutableListOf<ChartSegment>()
        var othersTotal = 0f

        dayEntries.forEach { entry ->
            if (entry.packageName in topApps) {
                segments.add(ChartSegment(
                    value = entry.total,
                    color = colorMap[entry.packageName] ?: Color.Gray,
                    label = entry.packageName
                ))
            } else {
                othersTotal += entry.total
            }
        }

        if (othersTotal > 0) {
            segments.add(ChartSegment(othersTotal, ColorOthers, null))
        }

        segments.sortBy { if (it.label == null) 999 else topApps.indexOf(it.label) }

        ChartDayData(
            dayLabel = dayLabel,
            date = dateStr,
            segments = segments,
            total = segments.sumOf { it.value.toDouble() }.toFloat()
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
fun StackedBarChart(
    data: List<ChartDayData>,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)

    Canvas(modifier = modifier) {
        val maxVal = data.maxOfOrNull { it.total }?.coerceAtLeast(10f) ?: 10f

        val chartBottom = size.height - 20.dp.toPx()
        val chartHeight = chartBottom

        val barWidth = size.width / (data.size * 2f)
        val spacing = size.width / data.size

        data.forEachIndexed { index, dayData ->
            val xOffset = (index * spacing) + (spacing / 2) - (barWidth / 2)

            var currentY = chartBottom

            dayData.segments.forEach { segment ->
                val segmentHeight = (segment.value / maxVal) * chartHeight

                if (segmentHeight > 0) {
                    drawRect(
                        color = segment.color,
                        topLeft = Offset(xOffset, currentY - segmentHeight),
                        size = Size(barWidth, segmentHeight)
                    )
                    currentY -= segmentHeight
                }
            }

            val measuredText = textMeasurer.measure(dayData.dayLabel, labelStyle)

            val textWidth = measuredText.size.width.toFloat()
            val textX = xOffset + (barWidth / 2) - (textWidth / 2)

            drawText(
                textLayoutResult = measuredText,
                topLeft = Offset(
                    textX,
                    size.height - 15.dp.toPx()
                )
            )
        }
    }
}

// ... Settings Screens ...

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

@Composable
fun PackageIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val targetSizePx = (48f * density).toInt().coerceAtLeast(64)

    val bitmapState = produceState<Bitmap?>(initialValue = null, key1 = packageName) {
        val cached = IconCache.get(packageName)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                if (drawable is BitmapDrawable &&
                    drawable.bitmap.width <= targetSizePx &&
                    drawable.bitmap.height <= targetSizePx) {
                    IconCache.put(packageName, drawable.bitmap)
                    drawable.bitmap
                } else {
                    val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)

                    // FIX: Fully qualified reference to Android Graphics Canvas to avoid conflict
                    val canvas = android.graphics.Canvas(bitmap)

                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    IconCache.put(packageName, bitmap)
                    bitmap
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    if (bitmapState.value != null) {
        Image(
            painter = BitmapPainter(bitmapState.value!!.asImageBitmap()),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .background(Color.LightGray.copy(alpha = 0.3f), CircleShape)
        )
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