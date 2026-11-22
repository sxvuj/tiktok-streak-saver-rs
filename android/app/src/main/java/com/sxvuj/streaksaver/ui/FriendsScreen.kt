@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Friends ($friendsCount)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Groups ($groupsCount)") })
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                placeholder = { Text(if (isGroup) "Search groups" else "Search friends") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { model.setAllEnabled(isGroup, true) }) { Text("Turn all on") }
                TextButton(onClick = { model.setAllEnabled(isGroup, false) }) { Text("Turn all off") }
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
                    title = "No friends or groups yet",
                    body = "Add the chats you keep streaks with, or import the list you exported from the original Streak Saver app.",
                ) {
                    Button(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import list") }
                    OutlinedButton(onClick = { adding = true }) { Text("Add manually") }
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    if (shown.isEmpty()) {
                        item {
                            EmptyState(
                                title = if (query.isBlank()) (if (isGroup) "No groups yet" else "No friends yet") else "No matches",
                                body = if (query.isBlank()) "Tap the button below to add one." else "Try a different name.",
                            )
                        }
                    }
                    items(shown, key = { it.id }) { chat ->
                        ChatRow(chat, now, onToggle = { model.setChatEnabled(chat.id, it) }, onClick = { editing = chat })
                    }
                    item {
                        val on = ui.chats.count { it.enabled }
                        Text(
                            "$friendsCount friends, $groupsCount groups, $on turned on",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(if (isGroup) "Add group" else "Add friend") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
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
private fun ChatRow(chat: Chat, now: Long, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Avatar(chat.title, chat.isGroup) },
        headlineContent = { Text(chat.title) },
        supportingContent = {
            Column {
                val last = chat.lastSentMs?.let { "sent ${relativeTime(it, now)}" } ?: "never sent"
                val counts = "${chat.successCount} sent, ${chat.failureCount} failed"
                val handle = if (chat.isGroup || chat.displayName.isBlank()) "" else "@${chat.username.trimStart('@')} · "
                Text("$handle$last · $counts")
                if (chat.consecutiveFailures > 0) {
                    Text(
                        "Failed ${chat.consecutiveFailures} times in a row",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = { Switch(checked = chat.enabled, onCheckedChange = onToggle) },
    )
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
                    singleLine = true,
                )
                OutlinedTextField(
                    value = display,
                    onValueChange = { display = it },
                    label = { Text("Display name (optional)") },
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
