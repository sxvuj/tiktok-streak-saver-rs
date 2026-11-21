package com.jon2g.tiktokstreaksaver.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jon2g.tiktokstreaksaver.rust.RustCore
import com.jon2g.tiktokstreaksaver.service.StreakService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storageDir = filesDir.absolutePath

        setContent {
            MaterialTheme {
                StreakSaverApp(
                    storageDir = storageDir,
                    onOpenLogin = {
                        startActivity(Intent(this, LoginWebViewActivity::class.java))
                    },
                    onRunNow = {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, StreakService::class.java)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun StreakSaverApp(
    storageDir: String,
    onOpenLogin: () -> Unit,
    onRunNow: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dashboard", "Friends", "History", "Profile")

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { idx, label ->
                    NavigationBarItem(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(storageDir, onOpenLogin, onRunNow)
                1 -> FriendsTab(storageDir)
                2 -> HistoryTab(storageDir)
                else -> ProfileTab(storageDir)
            }
        }
    }
}

@Composable
private fun DashboardTab(
    storageDir: String,
    onOpenLogin: () -> Unit,
    onRunNow: () -> Unit
) {
    val nextRunJson = remember(storageDir) { RustCore.computeNextRunJson(storageDir) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Streak Saver (Rust Core v2.2.0)", style = MaterialTheme.typography.headlineSmall)
        Text("Next run: $nextRunJson", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onOpenLogin) { Text("TikTok Login") }
            Button(onClick = onRunNow) { Text("Run Now") }
        }
    }
}

@Composable
private fun FriendsTab(storageDir: String) {
    var friendsJson by remember { mutableStateOf(RustCore.loadFriendsJson(storageDir)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Friends & Groups", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = friendsJson,
            onValueChange = { friendsJson = it },
            modifier = Modifier.fillMaxWidth().height(240.dp),
            label = { Text("RustCore Friends JSON") }
        )
        Button(onClick = { friendsJson = RustCore.loadFriendsJson(storageDir) }) {
            Text("Refresh")
        }
    }
}

@Composable
private fun HistoryTab(storageDir: String) {
    val stats = remember(storageDir) { RustCore.loadHistoryStatsJson(storageDir) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Run History & Stats", style = MaterialTheme.typography.headlineSmall)
        Text(stats, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileTab(storageDir: String) {
    val settings = remember(storageDir) { RustCore.loadSettingsJson(storageDir) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Profile & Automation Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(settings, style = MaterialTheme.typography.bodySmall)
    }
}
