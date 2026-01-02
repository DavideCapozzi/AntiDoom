package com.antidoom.app.ui

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences(context) }
    val scope = rememberCoroutineScope()

    val currentGlobalLimit by prefs.dailyLimit.collectAsState(initial = 100f)
    val isLocked by prefs.isSettingsLocked.collectAsState(initial = false)
    val trackedAppsPackageNames by prefs.trackedApps.collectAsState(initial = emptySet())
    val appLimits by prefs.appLimits.collectAsState(initial = emptyMap())

    // State per le app risolte (con icona e label)
    var activeAppsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // State for Per-App Limit Dialog
    var showDialogForPackage by remember { mutableStateOf<String?>(null) }

    // State for Confirmation Lock Dialog
    var showLockConfirmDialog by remember { mutableStateOf(false) }

    // Effetto per caricare le info delle app (Icone/Label) e filtrare quelle non installate
    LaunchedEffect(trackedAppsPackageNames) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val resolvedApps = trackedAppsPackageNames.mapNotNull { packageName ->
                try {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    AppInfo(
                        label = pm.getApplicationLabel(appInfo).toString(),
                        packageName = packageName,
                        icon = pm.getApplicationIcon(appInfo)
                    )
                } catch (e: PackageManager.NameNotFoundException) {
                    // L'app è nel database ma non nel telefono (es. trill/tiktok asia)
                    // La ignoriamo visivamente
                    null
                }
            }.sortedBy { it.label }
            activeAppsList = resolvedApps
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Limits Setup") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (!isLocked) showLockConfirmDialog = true else Toast.makeText(context, "Settings are locked for 24h", Toast.LENGTH_SHORT).show() }
                    ) {
                        Icon(
                            if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Lock Settings",
                            tint = if (isLocked) Color.Red else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {

            // 1. GLOBAL LIMIT SECTION
            item {
                Text("Global Limit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${currentGlobalLimit.toInt()} m",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (isLocked) Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                        }

                        Slider(
                            value = currentGlobalLimit,
                            onValueChange = {
                                if (!isLocked) scope.launch { prefs.updateDailyLimit(it) }
                                else Toast.makeText(context, "Locked!", Toast.LENGTH_SHORT).show()
                            },
                            valueRange = 10f..500f,
                            steps = 48,
                            enabled = !isLocked,
                            modifier = Modifier.alpha(if (isLocked) 0.5f else 1f)
                        )
                        Text("Applies to sum of all tracked apps.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. PER-APP LIMITS SECTION
            item {
                Text("Per-App Limits", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Set specific limits for your worst habits.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (activeAppsList.isEmpty()) {
                item {
                    Text("No installed tracked apps found.", modifier = Modifier.padding(16.dp), color = Color.Gray)
                }
            } else {
                items(activeAppsList, key = { it.packageName }) { app ->
                    val limit = appLimits[app.packageName]

                    AppLimitItem(
                        appInfo = app,
                        currentLimit = limit,
                        isLocked = isLocked,
                        onClick = { showDialogForPackage = app.packageName }
                    )
                }
            }
        }
    }

    // --- DIALOGS ---

    // 1. Lock Confirmation Dialog
    if (showLockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showLockConfirmDialog = false },
            title = { Text("Lock Settings?") },
            text = { Text("You will NOT be able to change limits or tracked apps for the next 24 hours.\n\nThis is designed to prevent you from cheating on your goals.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { prefs.setLock(24 * 60 * 60 * 1000L) }
                        showLockConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Lock for 24h")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLockConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }

    // 2. Set App Limit Dialog
    if (showDialogForPackage != null) {
        val pkg = showDialogForPackage!!
        val appName = activeAppsList.find { it.packageName == pkg }?.label ?: "App"
        val current = appLimits[pkg] ?: 100f
        var sliderValue by remember { mutableFloatStateOf(current) }
        var isEnabled by remember { mutableStateOf(appLimits.containsKey(pkg)) }

        if (isLocked) {
            showDialogForPackage = null
            Toast.makeText(context, "Settings are locked.", Toast.LENGTH_SHORT).show()
        } else {
            AlertDialog(
                onDismissRequest = { showDialogForPackage = null },
                title = { Text("Limit for $appName") },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isEnabled, onCheckedChange = { isEnabled = it })
                            Text("Enable specific limit")
                        }

                        if (isEnabled) {
                            Text("${sliderValue.toInt()} m", style = MaterialTheme.typography.headlineMedium)
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                valueRange = 10f..500f,
                                steps = 48
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            prefs.updateAppLimit(pkg, if (isEnabled) sliderValue else null)
                        }
                        showDialogForPackage = null
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialogForPackage = null }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun AppLimitItem(
    appInfo: AppInfo,
    currentLimit: Float?,
    isLocked: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            if (!isLocked) onClick()
            else {}
        },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = rememberAsyncImagePainter(appInfo.icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = appInfo.label, style = MaterialTheme.typography.bodyLarge)
                if (currentLimit != null) {
                    Text(
                        text = "Limit: ${currentLimit.toInt()} m",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text("Global Limit", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }

            if (isLocked) {
                Icon(Icons.Default.Lock, "Locked", tint = Color.LightGray, modifier = Modifier.size(16.dp))
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}