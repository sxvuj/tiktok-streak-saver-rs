@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    model: AppModel,
    now: Long,
    onLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = model.ui
    val run by model.run.collectAsState()
    val dash = ui.dashboard

    var pulling by remember { mutableStateOf(false) }
    LaunchedEffect(ui.updateChecking) { if (!ui.updateChecking) pulling = false }

    PullToRefreshBox(
        isRefreshing = pulling,
        onRefresh = {
            pulling = true
            model.refresh()
            model.checkUpdate()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val update = ui.update
            if (update != null && update.available) {
                SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        "Version ${update.latestVersion} is available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "You have ${update.currentVersion}.",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Button(onClick = { onOpenUrl(update.pageUrl) }) { Text("View release") }
                }
            }

            if (ui.loaded && !ui.session.valid) {
                SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        "Not logged in to TikTok",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Streaks cannot be sent until you log in. Your login stays on this phone.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Log in") }
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val progress = if (dash.enabledTargets > 0) dash.sentToday.toFloat() / dash.enabledTargets else 0f
                    ProgressRing(progress = progress) {
                        Text(
                            "${dash.sentToday}/${dash.enabledTargets}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                dash.enabledTargets == 0 -> "No chats turned on"
                                dash.remainingToday == 0 -> "Everyone is covered"
                                else -> "${dash.remainingToday} still to message"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${dash.enabledTargets} of ${dash.totalTargets} chats are on",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (run.running) {
                    val label = if (run.total > 0) "Sending ${run.index} of ${run.total}: ${run.current}" else run.current
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (run.total > 0) {
                        LinearProgressIndicator(
                            progress = { (run.index - 1).coerceAtLeast(0).toFloat() / run.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = { model.stopRun() }, enabled = !run.stopping) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (run.stopping) "Stopping" else "Stop")
                    }
                } else {
                    Button(onClick = { model.runNow() }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Run now")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(dash.friendsTotal.toString(), "Friends", Modifier.weight(1f))
                StatTile(dash.groupsTotal.toString(), "Groups", Modifier.weight(1f))
                StatTile(
                    dash.successRateTodayPct?.let { "${it.toInt()}%" } ?: "-",
                    "Success today",
                    Modifier.weight(1f),
                )
            }

            SectionCard(title = "Schedule") {
                val settings = ui.settings
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Next run", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val next = settings.nextRunMs
                    Text(
                        when {
                            !settings.scheduled -> "Automation is off"
                            next == null || next <= 0L -> if (ui.session.valid) "Not scheduled yet" else "Waiting for login"
                            else -> "${formatDateTime(next)} (${relativeTime(next, now)})"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Last run", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        dash.lastRunMs?.let { "${formatDateTime(it)} (${relativeTime(it, now)})" } ?: "No runs yet",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (settings.lastRunFailed) {
                    Text(
                        "The last attempt failed" + (settings.failureReason?.let { ": $it" } ?: "") + ". It is retried hourly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (settings.scheduled && !ui.exactAlarmsAllowed) {
                    Text(
                        "Exact alarms are off, so runs can be late. Allow them in the Profile tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            SectionCard(title = "Message") {
                var text by remember { mutableStateOf(ui.settings.messageText) }
                LaunchedEffect(ui.loaded) { if (ui.loaded) text = ui.settings.messageText }
                LaunchedEffect(text) {
                    if (ui.loaded && text.isNotBlank() && text != ui.settings.messageText) {
                        delay(600)
                        model.saveMessage(text)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message to send") },
                    supportingText = {
                        Text(
                            if (ui.settings.randomize) "Random messages are on, so this text is not used."
                            else "Saved automatically.",
                        )
                    },
                    minLines = 2,
                    maxLines = 5,
                )
            }
        }
    }
}
