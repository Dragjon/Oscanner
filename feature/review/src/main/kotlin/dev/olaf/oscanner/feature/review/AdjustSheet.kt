package dev.olaf.oscanner.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageFilter
import kotlin.math.roundToInt

/**
 * Brightness, contrast and sharpness for the current page. The page behind the sheet updates
 * when a slider is let go, not on every step, so the phone isn't re-rendering while you drag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdjustSheet(
    page: Page,
    onChange: (page: Page, brightness: Float, contrast: Float, detail: Float) -> Unit,
    onFlatten: (page: Page, on: Boolean) -> Unit,
    onDescreen: (page: Page, on: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var brightness by remember(page.id) { mutableFloatStateOf(page.edits.brightness) }
    var contrast by remember(page.id) { mutableFloatStateOf(page.edits.contrast) }
    var detail by remember(page.id) { mutableFloatStateOf(page.edits.detail) }
    val commit = { onChange(page, brightness, contrast, detail) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // No dimming: the page above the sheet is what you're judging.
        scrimColor = Color.Transparent,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlattenRow(page, onFlatten)
            SwitchRow(
                title = stringResource(R.string.review_descreen),
                description = stringResource(R.string.review_descreen_description),
                on = page.edits.descreen,
                onChange = { onDescreen(page, it) },
            )
            if (page.edits.filter == PageFilter.Ink) {
                Text(
                    stringResource(R.string.review_adjust_ink),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                LabeledSlider(stringResource(R.string.review_brightness), brightness, -1f..1f, { brightness = it }, commit)
                LabeledSlider(stringResource(R.string.review_contrast), contrast, -1f..1f, { contrast = it }, commit)
                LabeledSlider(stringResource(R.string.review_detail), detail, 0f..1f, { detail = it }, commit)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (page.edits.filter != PageFilter.Ink) {
                    TextButton(onClick = {
                        brightness = 0f
                        contrast = 0f
                        detail = 0f
                        commit()
                    }) { Text(stringResource(R.string.review_reset)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.review_done)) }
            }
        }
    }
}

/** Flattening is measured from the page's text, so it says when there wasn't enough to go on. */
@Composable
private fun FlattenRow(page: Page, onFlatten: (Page, Boolean) -> Unit) {
    val on = page.edits.flatten
    val measuring = on && page.edits.curve == null
    val nothingFound = on && page.edits.curve?.lines?.isEmpty() == true
    SwitchRow(
        title = stringResource(R.string.review_flatten),
        description = stringResource(
            when {
                measuring -> R.string.review_flatten_measuring
                nothingFound -> R.string.review_flatten_none
                else -> R.string.review_flatten_description
            },
        ),
        on = on,
        onChange = { onFlatten(page, it) },
    )
}

@Composable
private fun SwitchRow(title: String, description: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary),
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = colors.onSurface, modifier = Modifier.weight(1f))
            // Shown as a signed percentage: a number people can repeat on another page.
            val percent = (value * 100).roundToInt()
            Text(
                if (percent > 0 && range.start < 0f) "+$percent" else percent.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = range,
            // A two-way slider rests in the middle; filling the track from the left would read as "half".
            colors = SliderDefaults.colors(
                thumbColor = colors.primary,
                activeTrackColor = if (range.start < 0f) colors.surfaceContainerHighest else colors.primary,
                inactiveTrackColor = colors.surfaceContainerHighest,
            ),
        )
    }
}
