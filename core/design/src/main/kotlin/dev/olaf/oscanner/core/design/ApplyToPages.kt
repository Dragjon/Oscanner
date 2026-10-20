package dev.olaf.oscanner.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Which other pages an edit should also go to: every page at once, or the ones tapped. The page
 * being edited is shown ticked and can't be unticked: it has the edit already.
 *
 * [thumbs] are image paths for each page in order; [current] the page being edited.
 */
@Composable
fun ApplyToPagesDialog(
    title: String,
    thumbs: List<String?>,
    current: Int,
    onApply: (pages: Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var chosen by remember { mutableStateOf(thumbs.indices.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = thumbs.indices.toSet() }) { Text(stringResource(R.string.design_apply_all)) }
                    TextButton(onClick = { chosen = setOf(current) }) { Text(stringResource(R.string.design_apply_none)) }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 360.dp),
                ) {
                    itemsIndexed(thumbs) { index, path ->
                        val selected = index in chosen
                        val image by rememberPageImage(path, maxSide = 240)
                        val label = stringResource(R.string.design_page_number, index + 1)
                        Surface(
                            selected = selected,
                            enabled = index != current,
                            onClick = { chosen = if (selected) chosen - index else chosen + index },
                            shape = MaterialTheme.shapes.medium,
                            color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
                            border = if (selected) BorderStroke(2.dp, colors.primary) else null,
                            modifier = Modifier.semantics {
                                role = Role.Checkbox
                                this.selected = selected
                                contentDescription = label
                            },
                        ) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.75f)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(colors.surfaceContainerHighest),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit) }
                                    if (selected) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopStart)
                                                .padding(4.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(colors.primary),
                                            contentAlignment = Alignment.Center,
                                        ) { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp)) }
                                    }
                                }
                                Spacer(Modifier.size(4.dp))
                                Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val others = (chosen - current).size
            TextButton(onClick = { onApply(chosen - current) }, enabled = others > 0) {
                Text(pluralStringResource(R.plurals.design_apply_to, others, others))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.design_cancel), color = colors.onSurfaceVariant) }
        },
    )
}
