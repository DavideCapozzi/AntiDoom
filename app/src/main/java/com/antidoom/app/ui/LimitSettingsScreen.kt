package com.antidoom.app.ui

import android.content.pm.PackageManager
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

    // TWO SEPARATE LOCK STATES
    val isGeneralLocked by prefs.isGeneralLocked.collectAsState(initial = false)
    val isAppLimitsLocked by prefs.isAppLimitsLocked.collectAsState(initial = false)

    val trackedAppsPackageNames by prefs.trackedApps.collectAsState(initial = emptySet())
    val appLimits by prefs.appLimits.collectAsState(initial = emptyMap())

    // State per le app risolte (con icona e label)
    var activeAppsList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // State for Per-App Limit Dialog
    var showDialogForPackage by remember { mutableStateOf<String?>(null) }

    // State for Lock Confirmation Dialogs
    var showGeneralLockConfirm by remember { mutableStateOf(false) }
    var showAppLockConfirm by remember { mutableStateOf(false) }

    // Effetto per caricare le info delle app
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
                }
            )
        }
    ) { p ->
        LazyColumn(modifier = Modifier.padding(p).padding(16.dp)) {

            // 1. GLOBAL LIMIT SECTION
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Global Limit", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    // GENERAL LOCK ICON
                    IconButton(
                        onClick = {
                            if (!isGeneralLocked) showGeneralLockConfirm = true
                            else Toast.makeText(context, "Global settings locked for 24h", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            if (isGeneralLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "General Lock",
                            tint = if (isGeneralLocked) Color.Red else Color.Gray
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "${currentGlobalLimit.toInt()} m",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Slider(
                            value = currentGlobalLimit,
                            onValueChange = {
                                if (!isGeneralLocked) scope.launch { prefs.updateDailyLimit(it) }
                                else Toast.makeText(context, "Locked!", Toast.LENGTH_SHORT).show()
                            },
                            valueRange = 10f..500f,
                            steps = 48,
                            enabled = !isGeneralLocked,
                            modifier = Modifier.alpha(if (isGeneralLocked) 0.5f else 1f)
                        )
                        Text("Applies to sum of all tracked apps.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }

            // 2. PER-APP LIMITS SECTION
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Per-App Limits", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.weight(1f))
                    // APP LOCK ICON
                    IconButton(
                        onClick = {
                            if (!isAppLimitsLocked) showAppLockConfirm = true
                            else Toast.makeText(context, "App limits locked for 24h", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            if (isAppLimitsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "App Limits Lock",
                            tint = if (isAppLimitsLocked) Color.Red else Color.Gray
                        )
                    }
                }
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
                        isLocked = isAppLimitsLocked, // Using separate lock
                        onClick = { showDialogForPackage = app.packageName }
                    )
                }
            }
        }
    }

    // --- DIALOGS ---

    // 1. General Lock Confirmation
    if (showGeneralLockConfirm) {
        AlertDialog(
            onDismissRequest = { showGeneralLockConfirm = false },
            title = { Text("Lock Global Settings?") },
            text = { Text("Global Limit and App Selection will be locked for 24 hours.\n\nYou won't be able to remove tracked apps or increase the global budget.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { prefs.setGeneralLock(24 * 60 * 60 * 1000L) }
                        showGeneralLockConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Lock General")
                }
            },
            dismissButton = { TextButton(onClick = { showGeneralLockConfirm = false }) { Text("Cancel") } }
        )
    }

    // 2. App Limits Lock Confirmation
    if (showAppLockConfirm) {
        AlertDialog(
            onDismissRequest = { showAppLockConfirm = false },
            title = { Text("Lock App Limits?") },
            text = { Text("Specific App Limits will be locked for 24 hours.\n\nYou won't be able to change limits for individual apps.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { prefs.setAppLimitsLock(24 * 60 * 60 * 1000L) }
                        showAppLockConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Lock App Limits")
                }
            },
            dismissButton = { TextButton(onClick = { showAppLockConfirm = false }) { Text("Cancel") } }
        )
    }

    // 3. Set App Limit Dialog
    if (showDialogForPackage != null) {
        val pkg = showDialogForPackage!!
        val appName = activeAppsList.find { it.packageName == pkg }?.label ?: "App"
        val current = appLimits[pkg] ?: 100f
        var sliderValue by remember { mutableFloatStateOf(current) }
        var isEnabled by remember { mutableStateOf(appLimits.containsKey(pkg)) }

        if (isAppLimitsLocked) {
            showDialogForPackage = null
            Toast.makeText(context, "App limits are locked.", Toast.LENGTH_SHORT).show()
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