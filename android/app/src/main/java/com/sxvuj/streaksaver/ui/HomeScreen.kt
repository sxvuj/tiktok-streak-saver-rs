@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.ui.theme.Fit

@Composable
fun HomeScreen(
    model: AppModel,
    now: Long,
    onLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onEditMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = model.ui
    val run by model.run.collectAsState()
    val dash = ui.dashboard
    val colors = MaterialTheme.colorScheme

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
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenHeader("Today", subtitle = formatDayLong(now))

            val update = ui.update
            if (update != null && update.available) {
                SectionCard(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ) {
                    Text("Version ${update.latestVersion} is available", style = MaterialTheme.typography.titleLarge)
                    Text("You have ${update.currentVersion}.", style = MaterialTheme.typography.bodyMedium)
                    PillButton("View release", onClick = { onOpenUrl(update.pageUrl) })
                }
            }

            if (ui.loaded && !ui.session.valid) {
                SectionCard(
                    containerColor = colors.errorContainer,
                    contentColor = colors.onErrorContainer,
                ) {
                    Text("Not logged in to TikTok", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Streaks cannot be sent until you log in. Your login stays on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    PillButton(
                        "Log in",
                        onClick = onLogin,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.error,
                            contentColor = colors.onError,
                        ),
                    )
                }
            }

            // The hero card: the thick gauge with today's progress and the run button under it.
            SectionCard(containerColor = colors.surfaceContainerLow) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val progress = if (dash.enabledTargets > 0) dash.sentToday.toFloat() / dash.enabledTargets else 0f
                    GaugeRing(progress = progress, diameter = 220.dp, thickness = 22.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${dash.sentToday}", style = MaterialTheme.typography.displayLarge)
                            Text(
                                "of ${dash.enabledTargets} chats",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    when {
                        dash.enabledTargets == 0 -> "No chats turned on"
                        dash.remainingToday == 0 -> "Everyone is covered today"
                        else -> "${dash.remainingToday} still to message"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )

                if (run.running) {
                    val label = if (run.total > 0) "Sending ${run.index} of ${run.total}: ${run.current}" else run.current
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    val bar = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(CircleShape)
                    if (run.total > 0) {
                        LinearProgressIndicator(
                            progress = { (run.index - 1).coerceAtLeast(0).toFloat() / run.total },
                            modifier = bar,
                            color = Fit.colors.gauge,
                            trackColor = Fit.colors.gaugeTrack,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = bar,
                            color = Fit.colors.gauge,
                            trackColor = Fit.colors.gaugeTrack,
                        )
                    }
                    PillTonalButton(
                        if (run.stopping) "Stopping" else "Stop",
                        onClick = { model.stopRun() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.Close,
                        enabled = !run.stopping,
                    )
                } else {
                    PillButton(
                        "Run now",
                        onClick = { model.runNow() },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Filled.PlayArrow,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    dash.friendsTotal.toString(), "Friends", Modifier.weight(1f),
                    container = colors.primaryContainer, content = colors.onPrimaryContainer,
                )
                StatTile(
                    dash.groupsTotal.toString(), "Groups", Modifier.weight(1f),
                    container = Fit.colors.tealContainer, content = Fit.colors.onTealContainer,
                )
                StatTile(
                    dash.successRateTodayPct?.let { "${it.toInt()}%" } ?: "-", "Success today", Modifier.weight(1f),
                    container = Fit.colors.amberContainer, content = Fit.colors.onAmberContainer,
                )
            }

            SectionCard(title = "Schedule") {
                val settings = ui.settings
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Next run", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    val next = settings.nextRunMs
                    Text(
                        when {
                            !settings.scheduled -> "Automation is off"
                            next == null || next <= 0L -> if (ui.session.valid) "Not scheduled yet" else "Waiting for login"
                            else -> "${formatDateTime(next)} (${relativeTime(next, now)})"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Last run", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text(
                        dash.lastRunMs?.let { "${formatDateTime(it)} (${relativeTime(it, now)})" } ?: "No runs yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (settings.lastRunFailed) {
                    Text(
                        "The last attempt failed" + (settings.failureReason?.let { ": $it" } ?: "") + ". It is retried hourly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
                if (settings.scheduled && !ui.exactAlarmsAllowed) {
                    Text(
                        "Exact alarms are off, so runs can be late. Allow them in the Settings tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }

            MessageCard(ui, onEditMessages)
        }
    }
}

/** Shows what will be sent and opens the editor. */
@Composable
private fun MessageCard(ui: UiData, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val settings = ui.settings
    val pool = ui.randomMessages.messages

    SectionCard(title = "Message") {
        if (settings.randomize) {
            StatusPill(
                if (pool.isEmpty()) "Random, list empty" else "Random, ${pool.size} messages",
                container = Fit.colors.tealContainer,
                content = Fit.colors.onTealContainer,
            )
            if (pool.isEmpty()) {
                Text(
                    "Your random list is empty, so your single message is sent instead:",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text("\"${settings.messageText}\"", style = MaterialTheme.typography.titleMedium)
            } else {
                var sample by remember(pool) { mutableStateOf(pool.randomOrNull().orEmpty()) }
                Text(
                    "Example of what a chat can get:",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Text("\"$sample\"", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { sample = pool.randomOrNull().orEmpty() }) {
                    Text("Show another", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            StatusPill(
                "Single message",
                container = colors.primaryContainer,
                content = colors.onPrimaryContainer,
            )
            Text(
                if (settings.messageText.isBlank()) "No message set" else "\"${settings.messageText}\"",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        PillTonalButton("Edit messages", onClick = onEdit, modifier = Modifier.fillMaxWidth())
    }
}
