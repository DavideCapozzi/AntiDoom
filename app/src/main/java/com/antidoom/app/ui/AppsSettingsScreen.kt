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
import androidx.compose.ui.text.style.TextOverflow
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
                        IconButton(onClick = { Toast.makeText(context, "Selection locked", Toast.LENGTH_SHORT).show() }) {
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
            LazyColumn(
                modifier = Modifier.padding(p),
                // Optimization: Keep loaded items in memory slightly longer
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {

                if (coreAppsList.isNotEmpty()) {
                    item {
                        SectionHeader(title = "Core Doomscrolling Apps", color = MaterialTheme.colorScheme.primaryContainer)
                    }
                    items(
                        items = coreAppsList,
                        key = { it.packageName },
                        contentType = { "app_item" }
                    ) { app ->
                        AppItem(
                            app = app,
                            isTracked = app.packageName in trackedApps,
                            isLocked = isLocked,
                            onToggle = {
                                if (!isLocked) {
                                    scope.launch {
                                        val newSet = if (it) trackedApps + app.packageName else trackedApps - app.packageName
                                        prefs.updateTrackedApps(newSet)
                                    }
                                } else {
                                    Toast.makeText(context, "Locked: Cannot change apps", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                item {
                    SectionHeader(title = "Other Apps (Optional)", color = MaterialTheme.colorScheme.secondaryContainer)
                }

                items(
                    items = otherAppsList,
                    key = { it.packageName },
                    contentType = { "app_item" }
                ) { app ->
                    AppItem(
                        app = app,
                        isTracked = app.packageName in trackedApps,
                        isLocked = isLocked,
                        onToggle = {
                            if (!isLocked) {
                                scope.launch {
                                    val newSet = if (it) trackedApps + app.packageName else trackedApps - app.packageName
                                    prefs.updateTrackedApps(newSet)
                                }
                            } else {
                                Toast.makeText(context, "Locked: Cannot change apps", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, color: Color) {
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.contentColorFor(color)
        )
    }
}

// Lightweight Row implementation replacing ListItem
@Composable
fun AppItem(
    app: AppInfo,
    isTracked: Boolean,
    isLocked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp) // Fixed height helps measurement
            .clickable { onToggle(!isTracked) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PackageIcon(
            packageName = app.packageName,
            modifier = Modifier.size(40.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        Checkbox(
            checked = isTracked,
            onCheckedChange = onToggle,
            enabled = !isLocked,
            colors = CheckboxDefaults.colors(
                disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
    HorizontalDivider(thickness = 0.5.dp, color = Color.LightGray.copy(alpha = 0.5f))
}