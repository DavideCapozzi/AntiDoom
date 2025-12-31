package com.antidoom.app.ui

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antidoom.app.data.AppDatabase
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    
    // Using remember to ensure DB instance retrieval is efficient across recompositions
    val db = remember { AppDatabase.get(context) }
    
    // Safety: Collect flow with a default value to prevent UI flicker/crash
    val todayDistance by db.scrollDao()
        .getDailyDistance(LocalDate.now().toString())
        .collectAsState(initial = 0f)

    val safeDistance = todayDistance ?: 0f

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
            text = String.format("%.2f m", safeDistance),
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
            text = "1. Enable Accessibility",
            onClick = {
                openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))

        SetupButton(
            text = "2. Enable Overlay Permission",
            onClick = {
                openSettings(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            }
        )
    }
}

@Composable
fun SetupButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp)
    ) {
        Text(text)
    }
}

fun openSettings(context: Context, action: String) {
    try {
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback or log if specific settings page is not found
        e.printStackTrace()
    }
}
