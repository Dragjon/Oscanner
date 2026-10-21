package dev.olaf.oscanner.feature.document

import dev.olaf.oscanner.core.design.endTypingOutside
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.Tag

/** One line for the More sheet: "#tax · #2026 · Paid in March", or null when there's nothing yet. */
internal fun detailsSummary(tags: List<String>, note: String?): String? {
    val parts = tags.map { "#$it" } + listOfNotNull(note?.lineSequence()?.firstOrNull()?.take(60))
    return parts.joinToString(" · ").ifEmpty { null }
}

/**
 * Tags apply at once; the note is handed back on dismiss so typing never fights a database
 * round trip (the watermark field taught us that).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun DetailsSheet(
    tags: List<String>,
    note: String,
    allTags: List<Tag>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onDismiss: (note: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var draftNote by rememberSaveable { mutableStateOf(note) }
    var newTag by rememberSaveable { mutableStateOf("") }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = colors.onSurfaceVariant,
        unfocusedBorderColor = colors.outlineVariant,
        cursorColor = colors.primary,
    )
    fun addTyped() {
        if (newTag.isNotBlank()) onAddTag(newTag)
        newTag = ""
    }
    val typed = newTag.trim()
    val suggestions = allTags.map { it.name }
        .filter { name -> tags.none { it.equals(name, ignoreCase = true) } }
        .filter { typed.isEmpty() || it.contains(typed, ignoreCase = true) }
        .take(8)

    ModalBottomSheet(
        onDismissRequest = { onDismiss(draftNote) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .endTypingOutside()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.document_tags), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.document_tags_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag ->
                        val remove = stringResource(R.string.document_remove_tag, tag)
                        InputChip(
                            selected = true,
                            onClick = { onRemoveTag(tag) },
                            label = { Text("#$tag", style = MaterialTheme.typography.labelLarge) },
                            trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = MaterialTheme.shapes.medium,
                            border = null,
                            colors = InputChipDefaults.inputChipColors(
                                selectedContainerColor = colors.primaryContainer,
                                selectedLabelColor = colors.onPrimaryContainer,
                                selectedTrailingIconColor = colors.onPrimaryContainer,
                            ),
                            modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = remove },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it.replace("\n", "") },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.document_add_tag)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTyped() }),
                    shape = MaterialTheme.shapes.medium,
                    colors = fieldColors,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = ::addTyped, enabled = typed.isNotEmpty(), modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.document_add))
                }
            }
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { name ->
                        InputChip(
                            selected = false,
                            onClick = {
                                onAddTag(name)
                                newTag = ""
                            },
                            label = { Text("#$name", style = MaterialTheme.typography.labelLarge) },
                            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            shape = MaterialTheme.shapes.medium,
                            border = null,
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = colors.surfaceContainerHigh,
                                labelColor = colors.onSurfaceVariant,
                                leadingIconColor = colors.onSurfaceVariant,
                            ),
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.document_note),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedTextField(
                value = draftNote,
                onValueChange = { draftNote = it.take(2000) },
                placeholder = { Text(stringResource(R.string.document_note_hint)) },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.medium,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { onDismiss(draftNote) }, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.document_done))
            }
        }
    }
}
