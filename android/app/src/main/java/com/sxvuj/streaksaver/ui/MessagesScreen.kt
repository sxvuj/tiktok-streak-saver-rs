@file:OptIn(ExperimentalMaterial3Api::class)

package com.sxvuj.streaksaver.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sxvuj.streaksaver.data.MAX_MESSAGE_CHARS
import com.sxvuj.streaksaver.data.MAX_RANDOM_MESSAGES
import com.sxvuj.streaksaver.ui.theme.Fit
import kotlinx.coroutines.delay

/** Keeps an edited list across a screen rotation. */
private val DraftSaver = listSaver<SnapshotStateList<String>, String>(
    save = { it.toList() },
    restore = { it.toMutableStateList() },
)

/**
 * Edit what the app sends. Single message mode has one text that saves as you type. Random mode
 * has a list you can add to, edit, delete from, edit as plain text or reset to the built-in
 * phrases; list changes are kept as a draft until you tap Save.
 */
@Composable
fun MessagesScreen(model: AppModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ui = model.ui
    val settings = ui.settings
    val saved = ui.randomMessages
    val colors = MaterialTheme.colorScheme

    // --- The list being edited (random mode) ---
    val draft = rememberSaveable(saver = DraftSaver) { mutableStateListOf<String>() }
    var seeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.loaded, saved) {
        if (ui.loaded && !seeded) {
            draft.clear()
            draft.addAll(saved.messages)
            seeded = true
        }
    }
    val poolDirty = seeded && draft.toList() != saved.messages

    // --- The single message ---
    var text by rememberSaveable(ui.loaded) { mutableStateOf(settings.messageText) }
    LaunchedEffect(text) {
        // A blank message is never saved: the last saved text keeps being used.
        if (text.isNotBlank() && text != settings.messageText) {
            delay(600)
            model.saveMessage(text)
        }
    }

    var newText by rememberSaveable { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var bulkEditing by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun leave() {
        // The single message saves after a short pause; do not lose the last keystrokes.
        if (text.isNotBlank() && text != settings.messageText) model.saveMessage(text)
        onClose()
    }

    fun back() {
        if (poolDirty) confirmDiscard = true else leave()
    }

    fun addNew() {
        val clean = newText.trim()
        if (clean.isEmpty()) return
        when {
            draft.contains(clean) -> addError = "That message is already in the list"
            draft.size >= MAX_RANDOM_MESSAGES -> addError = "The list is full ($MAX_RANDOM_MESSAGES messages)"
            else -> {
                draft.add(0, clean)
                newText = ""
                addError = null
            }
        }
    }

    BackHandler { back() }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = if (poolDirty) 128.dp else 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text("Messages", style = MaterialTheme.typography.headlineLarge)
                }
            }

            item {
                SegmentedPills(
                    options = listOf("Single message", "Random list"),
                    selected = if (settings.randomize) 1 else 0,
                    onSelect = { model.setRandomize(it == 1) },
                )
            }

            if (!settings.randomize) {
                item {
                    SectionCard(title = "Your message") {
                        Text(
                            "The same text goes to every chat on every run.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = text,
                            onValueChange = { if (it.length <= MAX_MESSAGE_CHARS) text = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Message to send") },
                            shape = MaterialTheme.shapes.large,
                            isError = text.isBlank(),
                            supportingText = {
                                Text(
                                    if (text.isBlank()) "Type a message. Until then the last saved one is sent."
                                    else "Saved automatically. ${text.length}/$MAX_MESSAGE_CHARS",
                                )
                            },
                            minLines = 3,
                            maxLines = 8,
                        )
                    }
                }
                item {
                    Text(
                        "Want it to vary? Switch to Random list above. Your single message stays saved and is used if the list is ever empty.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            } else {
                item {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (saved.custom) {
                                StatusPill("Your list", container = colors.primaryContainer, content = colors.onPrimaryContainer)
                            } else {
                                StatusPill("Built-in list", container = Fit.colors.tealContainer, content = Fit.colors.onTealContainer)
                            }
                            StatusPill(
                                "${draft.size} / $MAX_RANDOM_MESSAGES",
                                container = colors.surfaceContainerHigh,
                                content = colors.onSurface,
                            )
                        }
                        Text(
                            "Each chat in a run gets a different message from this list, and none repeats until every message has been used once. Edits are only kept after you tap Save.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillTonalButton("Edit as text", onClick = { bulkEditing = true })
                            PillOutlinedButton(
                                "Reset",
                                onClick = { confirmReset = true },
                                enabled = saved.custom || poolDirty,
                            )
                        }
                    }
                }

                item {
                    SectionCard(title = "Add a message") {
                        OutlinedTextField(
                            value = newText,
                            onValueChange = {
                                if (it.length <= MAX_MESSAGE_CHARS) {
                                    newText = it.replace('\n', ' ')
                                    addError = null
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New message") },
                            shape = MaterialTheme.shapes.large,
                            singleLine = true,
                            isError = addError != null,
                            supportingText = { Text(addError ?: "${newText.length}/$MAX_MESSAGE_CHARS") },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { addNew() }),
                        )
                        PillButton(
                            "Add to list",
                            onClick = { addNew() },
                            modifier = Modifier.fillMaxWidth(),
                            icon = Icons.Filled.Add,
                            enabled = newText.isNotBlank(),
                        )
                    }
                }

                if (draft.isEmpty()) {
                    item {
                        SectionCard(containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
                            Text("The list is empty", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "While it is empty, the single message \"${settings.messageText}\" is sent instead. Add a message above or tap Reset for the built-in ones.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                itemsIndexed(draft) { index, message ->
                    MessageRow(
                        text = message,
                        onEdit = { editIndex = index },
                        onDelete = { if (index in draft.indices) draft.removeAt(index) },
                    )
                }
            }
        }

        if (poolDirty) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(colors.inverseSurface)
                    .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Unsaved changes",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.inverseOnSurface,
                )
                TextButton(
                    onClick = {
                        draft.clear()
                        draft.addAll(saved.messages)
                    },
                ) {
                    Text("Discard", color = colors.inversePrimary, fontWeight = FontWeight.ExtraBold)
                }
                PillButton(
                    "Save",
                    onClick = {
                        model.saveRandomMessages(draft.toList()) { kept ->
                            draft.clear()
                            draft.addAll(kept)
                        }
                    },
                )
            }
        }
    }

    // --- Dialogs ---

    val index = editIndex
    if (index != null && index in draft.indices) {
        var value by remember(index) { mutableStateOf(draft[index]) }
        AlertDialog(
            onDismissRequest = { editIndex = null },
            title = { Text("Edit message") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (it.length <= MAX_MESSAGE_CHARS) value = it.replace('\n', ' ') },
                    shape = MaterialTheme.shapes.large,
                    supportingText = { Text("${value.length}/$MAX_MESSAGE_CHARS") },
                    minLines = 2,
                    maxLines = 6,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = value.trim()
                        editIndex = null
                        if (clean.isNotEmpty() && index in draft.indices) {
                            draft[index] = clean
                            val unique = draft.distinct()
                            if (unique.size != draft.size) {
                                draft.clear()
                                draft.addAll(unique)
                            }
                        }
                    },
                    enabled = value.isNotBlank(),
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { editIndex = null }) { Text("Cancel") } },
        )
    }

    if (bulkEditing) {
        var value by remember { mutableStateOf(draft.joinToString("\n")) }
        AlertDialog(
            onDismissRequest = { bulkEditing = false },
            title = { Text("Edit as text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "One message per line. Blank lines and repeats are ignored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        minLines = 8,
                        maxLines = 12,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        bulkEditing = false
                        val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                        draft.clear()
                        draft.addAll(lines)
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { bulkEditing = false }) { Text("Cancel") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Back to the built-in list?") },
            text = {
                Text("This replaces your list with the ${saved.builtinCount} messages that come with the app and saves it right away.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        model.resetRandomMessages { builtin ->
                            draft.clear()
                            draft.addAll(builtin)
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Leave without saving?") },
            text = { Text("Your changes to the random list have not been saved.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        leave()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun MessageRow(text: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tooLong = text.length > MAX_MESSAGE_CHARS
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (tooLong) {
                Text(
                    "Over $MAX_MESSAGE_CHARS characters, it will be left out",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.error,
                )
            }
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit message") }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete message") }
    }
}
