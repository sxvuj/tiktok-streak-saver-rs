@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.data.RunEntry

@Composable
fun HistoryScreen(model: AppModel, now: Long, modifier: Modifier = Modifier) {
    val ui = model.ui
    val run by model.run.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.exportHistoryTo(uri)
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val rate = ui.stats.successRatePct
                    ProgressRing(progress = (rate / 100.0).toFloat()) {
                        Text(
                            if (ui.stats.sent + ui.stats.failed == 0) "-" else "${rate.toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("All time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${ui.stats.totalRuns} runs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${ui.stats.sent} messages sent, ${ui.stats.failed} failed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("streak-saver-history.txt") },
                        enabled = ui.history.isNotEmpty(),
                    ) { Text("Export log") }
                    OutlinedButton(
                        onClick = { confirmClear = true },
                        enabled = ui.history.isNotEmpty(),
                    ) { Text("Clear") }
                }
            }
        }

        if (ui.history.isEmpty()) {
            item {
                EmptyState(
                    title = "No runs yet",
                    body = "Every run, including ones that were skipped, shows up here with what happened.",
                )
            }
        }

        itemsIndexed(ui.history) { _, entry ->
            RunCard(entry, now, canRetry = !run.running, onRetry = { ids -> model.runNow(ids) })
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("This deletes the run log. Friends, groups and settings are not touched.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        model.clearHistory()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RunCard(entry: RunEntry, now: Long, canRetry: Boolean, onRetry: (List<String>) -> Unit) {
    var expanded by remember(entry.runMs) { mutableStateOf(false) }
    val expandable = entry.failed > 0 || entry.skipped || entry.sent > 0
    val problem = entry.failed > 0 || entry.skipped

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable) { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (problem) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(entry.runMs), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        relativeTime(entry.runMs, now),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when {
                        entry.skipped -> "Skipped"
                        entry.failed == 0 -> "All sent"
                        entry.sent == 0 -> "Failed"
                        else -> "Partly sent"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            if (entry.skipped) {
                Text(entry.error.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    buildString {
                        append("${entry.sent} sent")
                        if (entry.failed > 0) append(", ${entry.failed} failed")
                        append(" in ${formatDuration(entry.durationMs)}")
                        if (entry.targetedRetry) append(" (retry)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (expanded) {
                entry.failures.forEach { failure ->
                    Column {
                        Text(failure.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            failure.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val sentNames = entry.results.filter { !it.failed }.joinToString(", ") { it.title }
                if (sentNames.isNotEmpty()) {
                    Text(
                        "Sent to: $sentNames",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (entry.failed > 0) {
                    Button(
                        onClick = { onRetry(entry.failures.map { it.friendId }) },
                        enabled = canRetry,
                    ) { Text("Retry failed") }
                }
            }
        }
    }
}
