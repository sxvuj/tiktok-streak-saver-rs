@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.data.Chat

@Composable
fun FriendsScreen(model: AppModel, now: Long, modifier: Modifier = Modifier) {
    val ui = model.ui
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Chat?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val entrance = rememberEntrance()
    val listState = rememberLazyListState()
    // The FAB shrinks to an icon once you scroll, and grows back at the top, like Google's apps.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val isGroup = tab == 1
    val friendsCount = ui.chats.count { !it.isGroup }
    val groupsCount = ui.chats.count { it.isGroup }
    val shown = ui.chats.filter { chat ->
        chat.isGroup == isGroup &&
            (query.isBlank() ||
                chat.title.contains(query, ignoreCase = true) ||
                chat.username.contains(query, ignoreCase = true))
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importFrom(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) model.exportChatsTo(uri)
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Friends",
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .entrance(0, entrance),
                subtitle = "${ui.chats.count { it.enabled }} of ${ui.chats.size} turned on",
            )

            SegmentedPills(
                options = listOf("Friends ($friendsCount)", "Groups ($groupsCount)"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .entrance(1, entrance),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .entrance(2, entrance),
                placeholder = { Text(if (isGroup) "Search groups" else "Search friends") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = scaleIn(Motion.fastSpatial()) + fadeIn(),
                        exit = scaleOut(Motion.fastEffects()) + fadeOut(),
                    ) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                shape = CircleShape,
                singleLine = true,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .entrance(3, entrance),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { model.setAllEnabled(isGroup, true) }) {
                    Text("Turn all on", fontWeight = FontWeight.ExtraBold)
                }
                TextButton(onClick = { model.setAllEnabled(isGroup, false) }) {
                    Text("Turn all off", fontWeight = FontWeight.ExtraBold)
                }
                Box(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import list") },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export list") },
                            enabled = ui.chats.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                exportLauncher.launch("streak-saver-friends.json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove all") },
                            enabled = ui.chats.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                confirmClear = true
                            },
                        )
                    }
                }
            }

            if (ui.loaded && ui.chats.isEmpty()) {
                EmptyState(
                    modifier = Modifier.entrance(4, entrance),
                    title = "No friends or groups yet",
                    body = "Add the chats you keep streaks with, or import the list you exported from the original Streak Saver app.",
                ) {
                    PillButton("Import list", onClick = { importLauncher.launch(arrayOf("*/*")) })
                    PillOutlinedButton("Add manually", onClick = { adding = true })
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (shown.isEmpty()) {
                        item(key = "empty") {
                            EmptyState(
                                modifier = Modifier.animateItem(),
                                title = if (query.isBlank()) (if (isGroup) "No groups yet" else "No friends yet") else "No matches",
                                body = if (query.isBlank()) "Tap the button below to add one." else "Try a different name.",
                            )
                        }
                    }
                    // animateItem: rows fade in and out and glide to their new place when the list
                    // changes (switching Friends/Groups, searching, adding, deleting).
                    itemsIndexed(shown, key = { _, chat -> chat.id }) { index, chat ->
                        ChatRow(
                            chat, now,
                            onToggle = { model.setChatEnabled(chat.id, it) },
                            onClick = { editing = chat },
                            modifier = Modifier
                                .animateItem(
                                    fadeInSpec = Motion.defaultEffects(),
                                    placementSpec = Motion.offsetSpec,
                                    fadeOutSpec = Motion.fastEffects(),
                                )
                                .entrance(index + 4, entrance, key = chat.id),
                        )
                    }
                    item(key = "footer") {
                        val on = ui.chats.count { it.enabled }
                        Text(
                            "$friendsCount friends, $groupsCount groups, $on turned on",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            }
        }

        val fabSource = remember { MutableInteractionSource() }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = {
                AnimatedContent(targetState = isGroup, transitionSpec = { rollingText(up = targetState) }, label = "fabText") { group ->
                    Text(if (group) "Add group" else "Add friend", fontWeight = FontWeight.ExtraBold)
                }
            },
            expanded = fabExpanded,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            interactionSource = fabSource,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .pressScale(fabSource, 0.92f)
                .entrance(5, entrance),
        )
    }

    if (adding) {
        ChatDialog(
            initial = null,
            isGroup = isGroup,
            onDismiss = { adding = false },
            onSave = { name, display ->
                adding = false
                model.addChat(name, display, isGroup)
            },
            onDelete = null,
        )
    }

    val target = editing
    if (target != null) {
        ChatDialog(
            initial = target,
            isGroup = target.isGroup,
            onDismiss = { editing = null },
            onSave = { name, display ->
                editing = null
                model.editChat(target.id, name, display)
            },
            onDelete = {
                editing = null
                model.deleteChat(target.id)
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Remove all chats?") },
            text = { Text("This removes every friend and group and their counters. History is kept. Export first if you want a backup.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        model.clearChats()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ChatRow(
    chat: Chat,
    now: Long,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    // Turned-off chats fade back a little so the list shows at a glance who gets a message.
    val dim by animateFloatAsState(if (chat.enabled) 1f else 0.6f, Motion.defaultEffects(), label = "chatDim")
    Card(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(source, 0.97f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(chat.title, chat.isGroup, Modifier.graphicsLayer { alpha = dim })
            Column(
                Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = dim },
            ) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium)
                val last = chat.lastSentMs?.let { "sent ${relativeTime(it, now)}" } ?: "never sent"
                val counts = "${chat.successCount} sent, ${chat.failureCount} failed"
                val handle = if (chat.isGroup || chat.displayName.isBlank()) "" else "@${chat.username.trimStart('@')} · "
                Text(
                    "$handle$last · $counts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (chat.consecutiveFailures > 0) {
                    Text(
                        "Failed ${chat.consecutiveFailures} times in a row",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = chat.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ChatDialog(
    initial: Chat?,
    isGroup: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial?.username ?: "") }
    var display by remember { mutableStateOf(initial?.displayName ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    initial != null -> "Edit chat"
                    isGroup -> "Add group"
                    else -> "Add friend"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (isGroup) "Group name, exactly as TikTok shows it" else "TikTok username") },
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = display,
                    onValueChange = { display = it },
                    label = { Text("Display name (optional)") },
                    shape = MaterialTheme.shapes.large,
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, display) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
