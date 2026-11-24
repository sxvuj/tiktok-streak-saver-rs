@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.data.Session

private const val REPO_URL = "https://github.com/sxvuj/tiktok-streak-saver-rs"
private const val ORIGINAL_URL = "https://github.com/Jon2G/TiktokStreakSaver"

@Composable
fun ProfileScreen(
    model: AppModel,
    now: Long,
    onLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = model.ui
    val settings = ui.settings
    val context = LocalContext.current

    var editingName by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SessionCard(
            session = ui.session,
            now = now,
            onEditName = { editingName = true },
            onVerify = { model.verifySession() },
            onLogin = onLogin,
            onLogout = { confirmLogout = true },
        )

        SectionCard(title = "Automation") {
            SwitchRow(
                title = "Background automation",
                subtitle = if (settings.scheduled) "Sends streaks on its own, even when the app is closed."
                else "Off. Streaks are only sent when you tap Run now.",
                checked = settings.scheduled,
                onCheckedChange = { model.setScheduled(it) },
            )
            if (settings.scheduled) {
                HorizontalDivider()
                SwitchRow(
                    title = "Send at a fixed time",
                    subtitle = "Uses your phone's local time.",
                    checked = settings.useFixedTime,
                    onCheckedChange = { model.setUseFixedTime(it) },
                )
                if (settings.useFixedTime) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { pickingTime = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Time of day", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                formatClock(settings.fixedHour, settings.fixedMinute),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        FilledTonalButton(onClick = { pickingTime = true }) { Text("Change") }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Repeat every", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Between 1 and 23 hours. Keep it under 24 so a streak never lapses.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { model.setInterval(settings.intervalHours - 1) },
                            enabled = settings.intervalHours > 1,
                        ) { Text("-") }
                        Text(
                            "${settings.intervalHours} h",
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedButton(
                            onClick = { model.setInterval(settings.intervalHours + 1) },
                            enabled = settings.intervalHours < 23,
                        ) { Icon(Icons.Filled.Add, contentDescription = "More hours") }
                    }
                }
            }
        }

        SectionCard(title = "Messages") {
            SwitchRow(
                title = "Random messages",
                subtitle = "Pick a different built-in message for each chat instead of your own text.",
                checked = settings.randomize,
                onCheckedChange = { model.setRandomize(it) },
            )
            SwitchRow(
                title = "Skip chats that can't be found",
                subtitle = "Keep going when a chat is missing. When off, the run stops at the first one.",
                checked = settings.skipUnreachable,
                onCheckedChange = { model.setSkipUnreachable(it) },
            )
            Text("Chat order", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityChip("Mixed", "mixed", settings.chatPriority) { model.setPriority(it) }
                PriorityChip("Friends first", "friends_first", settings.chatPriority) { model.setPriority(it) }
                PriorityChip("Groups first", "groups_first", settings.chatPriority) { model.setPriority(it) }
            }
        }

        SectionCard(title = "Reliability") {
            SwitchRow(
                title = "Send early when the battery is low",
                subtitle = "Runs once before the phone dies if today's messages are still due.",
                checked = settings.sendOnBatteryLow,
                onCheckedChange = { model.setBatteryLow(it) },
            )
            HorizontalDivider()
            ActionRow(
                title = "Battery optimization",
                subtitle = if (ui.ignoringBatteryOptimizations) "Unrestricted. Good." else "Android may delay runs. Set this app to Unrestricted.",
                good = ui.ignoringBatteryOptimizations,
                action = "Open",
            ) { openSettings(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            ActionRow(
                title = "Exact alarms",
                subtitle = if (ui.exactAlarmsAllowed) "Allowed. Runs start on time." else "Not allowed. Runs can be late.",
                good = ui.exactAlarmsAllowed,
                action = "Open",
            ) {
                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                } else {
                    appDetails(context)
                }
                openSettings(context, intent)
            }
            ActionRow(
                title = "Notifications",
                subtitle = if (ui.notificationsAllowed) "On. You see the result of every run." else "Off. You will not see results.",
                good = ui.notificationsAllowed,
                action = "Open",
            ) {
                val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    appDetails(context)
                }
                openSettings(context, intent)
            }
        }

        SectionCard(title = "About") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Streak Saver RS", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Version ${model.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val update = ui.update
                    if (update != null) {
                        Text(
                            if (update.available) "Version ${update.latestVersion} is available" else "You have the latest version",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (update.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledTonalButton(onClick = { model.checkUpdate() }, enabled = !ui.updateChecking) {
                    Text(if (ui.updateChecking) "Checking" else "Check for updates")
                }
            }
            if (ui.update?.available == true) {
                Button(onClick = { onOpenUrl(ui.update?.pageUrl ?: REPO_URL) }) { Text("View release") }
            }
            Text(
                "Everything stays on this phone. The app only talks to TikTok (to send messages) and to GitHub (to look for updates).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenUrl(REPO_URL) }) { Text("Source code") }
                TextButton(onClick = { onOpenUrl(ORIGINAL_URL) }) { Text("Original app") }
            }
        }
    }

    if (editingName) {
        var name by remember { mutableStateOf(ui.session.displayName) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Your name") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shown on this screen only") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingName = false
                        model.setDisplayName(name)
                    },
                    enabled = name.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }

    if (pickingTime) {
        val state = rememberTimePickerState(
            initialHour = settings.fixedHour,
            initialMinute = settings.fixedMinute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text("Time of day") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickingTime = false
                        model.setFixedTime(state.hour, state.minute)
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out of TikTok?") },
            text = { Text("This clears the TikTok login stored by this app and pauses automation until you log in again. Your friends and history stay.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        model.logout()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionCard(
    session: Session,
    now: Long,
    onEditName: () -> Unit,
    onVerify: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    SectionCard(
        containerColor = if (session.valid) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(session.displayName.ifBlank { "T" }, isGroup = false)
            Column(Modifier.weight(1f)) {
                Text(
                    session.displayName.ifBlank { "TikTok account" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (session.valid) {
                        "Logged in" + (session.lastCheckMs?.let { ", checked ${relativeTime(it, now)}" } ?: "")
                    } else {
                        "Not logged in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.valid) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onEditName) { Icon(Icons.Filled.Edit, contentDescription = "Edit name") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.valid) {
                OutlinedButton(onClick = onVerify) { Text("Verify") }
                OutlinedButton(onClick = onLogout) { Text("Log out") }
            } else {
                Button(onClick = onLogin) { Text("Log in to TikTok") }
                OutlinedButton(onClick = onVerify) { Text("Check again") }
            }
        }
    }
}

@Composable
private fun PriorityChip(label: String, value: String, current: String, onSelect: (String) -> Unit) {
    FilterChip(
        selected = current == value,
        onClick = { onSelect(value) },
        label = { Text(label) },
    )
}

@Composable
private fun ActionRow(title: String, subtitle: String, good: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (good) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onClick) { Text(action) }
    }
}

private fun appDetails(context: Context): Intent =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** Opens a system settings page, falling back to this app's details page when the device lacks it. */
private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e2: Exception) {
            // Nothing can open settings on this device.
        }
    }
}
