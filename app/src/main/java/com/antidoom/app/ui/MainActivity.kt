package com.antidoom.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.antidoom.app.data.AppDatabase
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    // Nota: Flow collectAsStateWithLifecycle è meglio, ma per brevità usiamo collectAsState
    val todayDistance by db.scrollDao()
        .getDailyDistance(LocalDate.now().toString())
        .collectAsState(initial = 0f)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AntiDoom Tracker", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = String.format("%.2f Meters", todayDistance ?: 0f),
            style = MaterialTheme.typography.displayLarge
        )
        Text("Scrolled Today")

        Spacer(modifier = Modifier.height(48.dp))

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) {
            Text("1. Enable Accessibility")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        }) {
            Text("2. Enable Overlay")
        }
    }
}
