@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.data.Session
import com.sxvuj.streaksaver.ui.theme.Fit
import com.sxvuj.streaksaver.ui.theme.supportsDynamicColor
import kotlinx.coroutines.delay

private const val REPO_URL = "https://github.com/sxvuj/tiktok-streak-saver-rs"
private const val ORIGINAL_URL = "https://github.com/Jon2G/TiktokStreakSaver"

private val CHAT_ORDER_VALUES = listOf("mixed", "friends_first", "groups_first")

@Composable
fun SettingsScreen(
    model: AppModel,
    now: Long,
    onLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onEditMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = model.ui
    val settings = ui.settings
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    var editingName by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val entrance = rememberEntrance()

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Settings", modifier = Modifier.entrance(0, entrance))

        SessionCard(
            modifier = Modifier.entrance(1, entrance),
            session = ui.session,
            now = now,
            onEditName = { editingName = true },
            onVerify = { model.verifySession() },
            onLogin = onLogin,
            onLogout = { confirmLogout = true },
        )

        SectionCard(title = "Automation", modifier = Modifier.entrance(2, entrance)) {
            SwitchRow(
                title = "Background automation",
                subtitle = if (settings.scheduled) "Sends streaks on its own, even when the app is closed."
                else "Off. Streaks are only sent when you tap Run now.",
                checked = settings.scheduled,
                onCheckedChange = { model.setScheduled(it) },
            )
            AnimatedVisibility(
                visible = settings.scheduled,
                enter = expandVertically(Motion.sizeSpec) + fadeIn(tween(220, delayMillis = 60)),
                exit = shrinkVertically(Motion.sizeSpec) + fadeOut(tween(90)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    HorizontalDivider(color = colors.outlineVariant)
                    SwitchRow(
                        title = "Send at a fixed time",
                        subtitle = "Uses your phone's local time.",
                        checked = settings.useFixedTime,
                        onCheckedChange = { model.setUseFixedTime(it) },
                    )
                    AnimatedContent(
                        targetState = settings.useFixedTime,
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = { softSwap() },
                        label = "scheduleMode",
                    ) { fixedTime ->
                        if (fixedTime) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pickingTime = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Time of day", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    AnimatedContent(
                                        targetState = formatClock(settings.fixedHour, settings.fixedMinute),
                                        transitionSpec = { rollingText() },
                                        label = "clock",
                                    ) { clock ->
                                        Text(
                                            clock,
                                            style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                                            color = colors.primary,
                                        )
                                    }
                                }
                                PillTonalButton("Change", onClick = { pickingTime = true })
                            }
                        } else {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Repeat every", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Between 1 and 23 hours. Keep it under 24 so a streak never lapses.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                StepButton("-", enabled = settings.intervalHours > 1) { model.setInterval(settings.intervalHours - 1) }
                                // Tapping + rolls the number up, tapping - rolls it down.
                                AnimatedContent(
                                    targetState = settings.intervalHours,
                                    transitionSpec = { rollingText(up = targetState > initialState) },
                                    label = "interval",
                                ) { hours ->
                                    Text(
                                        "$hours h",
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                        style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                    )
                                }
                                StepButton("+", enabled = settings.intervalHours < 23) { model.setInterval(settings.intervalHours + 1) }
                            }
                        }
                    }
                }
            }
        }

        SectionCard(title = "Messages", modifier = Modifier.entrance(3, entrance)) {
            val pool = ui.randomMessages
            ActionRow(
                title = "Edit messages",
                subtitle = when {
                    !settings.randomize -> "Single message: \"${settings.messageText.take(60)}\""
                    pool.messages.isEmpty() -> "Random, but the list is empty so your single message is used"
                    pool.custom -> "Random, ${pool.messages.size} of your own messages"
                    else -> "Random, ${pool.messages.size} built-in messages"
                },
                action = "Edit",
                onClick = onEditMessages,
            )
            HorizontalDivider(color = colors.outlineVariant)
            SwitchRow(
                title = "Random messages",
                subtitle = "Pick a different message from your list for each chat instead of one fixed text.",
                checked = settings.randomize,
                onCheckedChange = { model.setRandomize(it) },
            )
            SwitchRow(
                title = "Skip chats that can't be found",
                subtitle = "Keep going when a chat is missing. When off, the run stops at the first one.",
                checked = settings.skipUnreachable,
                onCheckedChange = { model.setSkipUnreachable(it) },
            )
            Text("Chat order", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            SegmentedPills(
                options = listOf("Mixed", "Friends first", "Groups first"),
                selected = CHAT_ORDER_VALUES.indexOf(settings.chatPriority).coerceAtLeast(0),
                onSelect = { model.setPriority(CHAT_ORDER_VALUES[it]) },
            )
        }

        SectionCard(title = "Reliability", modifier = Modifier.entrance(4, entrance)) {
            SwitchRow(
                title = "Send early when the battery is low",
                subtitle = "Runs once before the phone dies if today's messages are still due.",
                checked = settings.sendOnBatteryLow,
                onCheckedChange = { model.setBatteryLow(it) },
            )
            HorizontalDivider(color = colors.outlineVariant)
            ActionRow(
                title = "Battery optimization",
                subtitle = if (ui.ignoringBatteryOptimizations) "Unrestricted. Good." else "Android may delay runs. Set this app to Unrestricted.",
                good = ui.ignoringBatteryOptimizations,
                action = "Open",
            ) { openSettings(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            ActionRow(
                title = "Exact alarms",
                subtitle = if (ui.exactAlarmsAllowed) "Allowed. Runs start on time." else "Not allowed. Runs can be a few minutes late.",
                good = ui.exactAlarmsAllowed,
                action = "Open",
            ) {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                } else {
                    appDetails(context)
                }
                openSettings(context, intent)
            }
        }

        AppearanceCard(
            dynamicColor = model.dynamicColor,
            onDynamicColorChange = { model.setDynamicColor(it) },
            modifier = Modifier.entrance(5, entrance),
        )

        SectionCard(title = "About", modifier = Modifier.entrance(6, entrance)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Streak Saver RS", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Version ${model.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    val update = ui.update
                    if (update != null) {
                        Text(
                            if (update.available) "Version ${update.latestVersion} is available" else "You have the latest version",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (update.available) colors.primary else colors.onSurfaceVariant,
                        )
                    }
                }
                PillTonalButton(
                    if (ui.updateChecking) "Checking" else "Check",
                    onClick = { model.checkUpdate() },
                    enabled = !ui.updateChecking,
                )
            }
            if (ui.update?.available == true) {
                PillButton("View release", onClick = { onOpenUrl(ui.update?.pageUrl ?: REPO_URL) })
            }
            Text(
                "Everything stays on this phone. The app only talks to TikTok (to send messages) and to GitHub (to look for updates).",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                "Typeface: Google Sans Flex (rounded instances), used under the SIL Open Font License 1.1.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOpenUrl(REPO_URL) }) {
                    Text("Source code", style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { onOpenUrl(ORIGINAL_URL) }) {
                    Text("Original app", style = MaterialTheme.typography.labelLarge)
                }
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
                    shape = MaterialTheme.shapes.large,
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
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = Modifier.pressScale(source, 0.85f),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Dynamic color (Material You / Monet) switch with a live preview of the current palette. The
 * swatches and the whole app glide to the new colors when it is flipped.
 */
@Composable
private fun AppearanceCard(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    SectionCard(title = "Appearance", modifier = modifier) {
        SwitchRow(
            title = "Dynamic color",
            subtitle = if (supportsDynamicColor) {
                "Take the app's colors from your wallpaper (Material You). Off uses the blue Fitbit palette."
            } else {
                "Needs Android 12 or newer. The blue Fitbit palette is used."
            },
            checked = dynamicColor && supportsDynamicColor,
            onCheckedChange = onDynamicColorChange,
            enabled = supportsDynamicColor,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf(colors.primary, colors.secondary, colors.tertiary, colors.primaryContainer, Fit.colors.tealContainer)
                .forEachIndexed { index, swatch ->
                    Swatch(swatch, index, dynamicColor)
                }
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = if (dynamicColor && supportsDynamicColor) "Wallpaper" else "Fitbit blue",
                transitionSpec = { rollingText() },
                label = "paletteName",
            ) { name ->
                Text(name, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** One palette dot. On every switch flip the dots hop one after another, like a little wave. */
@Composable
private fun Swatch(color: Color, index: Int, trigger: Boolean) {
    val hop = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        delay(index * 60L)
        hop.animateTo(1.3f, Motion.fastEffects())
        hop.animateTo(1f, Motion.bouncy())
    }
    Box(
        Modifier
            .size(28.dp)
            .graphicsLayer {
                scaleX = hop.value
                scaleY = hop.value
            }
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun SessionCard(
    modifier: Modifier = Modifier,
    session: Session,
    now: Long,
    onEditName: () -> Unit,
    onVerify: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SectionCard(
        modifier = modifier,
        containerColor = if (session.valid) Fit.colors.tealContainer else colors.surfaceContainerHigh,
        contentColor = if (session.valid) Fit.colors.onTealContainer else colors.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(session.displayName.ifBlank { "T" }, isGroup = session.valid)
            Column(Modifier.weight(1f)) {
                Text(
                    session.displayName.ifBlank { "TikTok account" },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    if (session.valid) {
                        "Logged in" + (session.lastCheckMs?.let { ", checked ${relativeTime(it, now)}" } ?: "")
                    } else {
                        "Not logged in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (session.valid) Fit.colors.onTealContainer else colors.error,
                )
            }
            IconButton(onClick = onEditName) { Icon(Icons.Filled.Edit, contentDescription = "Edit name") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.valid) {
                PillOutlinedButton("Verify", onClick = onVerify)
                PillOutlinedButton("Log out", onClick = onLogout)
            } else {
                PillButton("Log in to TikTok", onClick = onLogin)
                PillOutlinedButton("Check again", onClick = onVerify)
            }
        }
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
