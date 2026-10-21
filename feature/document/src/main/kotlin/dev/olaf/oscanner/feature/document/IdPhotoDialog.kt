package dev.olaf.oscanner.feature.document

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.IdBackground
import dev.olaf.oscanner.core.model.IdPhotoSize

/** Asks what size of ID photo to make from the page's photo, and what to put behind the person. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IdPhotoDialog(onMake: (IdPhotoSize, IdBackground) -> Unit, onDismiss: () -> Unit) {
    var size by rememberSaveable { mutableStateOf(IdPhotoSize.Mm35x45) }
    var background by rememberSaveable { mutableStateOf(IdBackground.White) }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_id_photo_title)) },
        text = {
            Column {
                Text(stringResource(R.string.document_id_photo_body), color = colors.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                for (option in IdPhotoSize.entries) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == size, onClick = { size = option }, role = Role.RadioButton)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == size, onClick = null)
                        Text(stringResource(option.label()), Modifier.padding(start = 12.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.document_id_photo_background), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in IdBackground.entries) {
                        FilterChip(selected = option == background, onClick = { background = option }, label = { Text(stringResource(option.label())) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMake(size, background) }) { Text(stringResource(R.string.document_id_photo_make)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
        },
    )
}

private fun IdPhotoSize.label() = when (this) {
    IdPhotoSize.Mm35x45 -> R.string.document_id_size_35x45
    IdPhotoSize.In2x2 -> R.string.document_id_size_2x2
    IdPhotoSize.Mm33x48 -> R.string.document_id_size_33x48
}

private fun IdBackground.label() = when (this) {
    IdBackground.White -> R.string.document_id_background_white
    IdBackground.LightGray -> R.string.document_id_background_gray
    IdBackground.LightBlue -> R.string.document_id_background_blue
    IdBackground.Keep -> R.string.document_id_background_keep
}
