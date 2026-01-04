package com.antidoom.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.antidoom.app.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val prefs = remember { UserPreferences.get(context) }
    val scope = rememberCoroutineScope()

    val isGeneralLocked by prefs.isGeneralLocked.collectAsState(initial = false)
    val isAppLimitsLocked by prefs.isAppLimitsLocked.collectAsState(initial = false)
    val isLocked = isGeneralLocked || isAppLimitsLocked

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
            // Optimization: We do NOT load Drawables here anymore to save memory
            val apps = pm.queryIntentActivities(intent, 0)
                .map { resolveInfo ->
                    AppInfo(
                        label = resolveInfo.loadLabel(pm).toString(),
                        packageName = resolveInfo.activityInfo.packageName
                    )
                }
                .filter { it.packageName != context.packageName }
                .sortedBy { it.label }
                .distinctBy { it.packageName }

            installedApps = apps
            isLoading = false
        }
    }

    val coreAppsList = remember(installedApps) {
        installedApps.filter { it.packageName in corePackages }
    }
    val otherAppsList = remember(installedApps) {
        installedApps.filter { it.packageName !in corePackages }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracked Apps") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (isLocked) {
                        IconButton(onClick = { Toast.makeText(context, "Selection locked by active timer", Toast.LENGTH_SHORT).show() }) {
                            Icon(Icons.Default.Lock, "Locked", tint = Color.Red)
                        }
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
                        AppItem(app, app.packageName in trackedApps, isLocked) { isChecked ->
                            if (isLocked) {
                                Toast.makeText(context, "Locked: Cannot change apps while timer is active", Toast.LENGTH_SHORT).show()
                                return@AppItem
                            }
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
                    AppItem(app, app.packageName in trackedApps, isLocked) { isChecked ->
                        if (isLocked) {
                            Toast.makeText(context, "Locked: Cannot change apps while timer is active", Toast.LENGTH_SHORT).show()
                            return@AppItem
                        }
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
fun AppItem(app: AppInfo, isTracked: Boolean, isLocked: Boolean, onToggle: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(app.label) },
        leadingContent = {
            // Using the new helper composable for memory efficiency
            PackageIcon(
                packageName = app.packageName,
                modifier = Modifier.size(40.dp)
            )
        },
        trailingContent = {
            Checkbox(
                checked = isTracked,
                onCheckedChange = onToggle,
                enabled = !isLocked,
                colors = CheckboxDefaults.colors(
                    disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )
        },
        modifier = Modifier.clickable { onToggle(!isTracked) }
    )
    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray)
}