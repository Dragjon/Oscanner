package dev.olaf.oscanner.feature.capture

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.ScanFixes

/**
 * Fixes the camera applies to every page as it's saved. Kept for later scans too; each one is an
 * ordinary page edit, so it can be undone on any page afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoFixSheet(fixes: ScanFixes, onChange: (ScanFixes) -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainer,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.capture_fixes_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.padding(start = 4.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.capture_fixes_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            FixSwitch(R.string.capture_fix_handwriting, R.string.capture_fix_handwriting_description, fixes.removeHandwriting) {
                onChange(fixes.copy(removeHandwriting = it))
            }
            FixSwitch(R.string.capture_fix_fingers, R.string.capture_fix_fingers_description, fixes.removeFingers) {
                onChange(fixes.copy(removeFingers = it))
            }
            FixSwitch(R.string.capture_fix_flatten, R.string.capture_fix_flatten_description, fixes.flatten) {
                onChange(fixes.copy(flatten = it))
            }
            FixSwitch(R.string.capture_fix_descreen, R.string.capture_fix_descreen_description, fixes.descreen) {
                onChange(fixes.copy(descreen = it))
            }
            FixSwitch(R.string.capture_fix_timestamp, R.string.capture_fix_timestamp_description, fixes.timestamp) {
                onChange(fixes.copy(timestamp = it))
            }
            Text(
                stringResource(R.string.capture_fix_look),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOOKS.forEach { (look, label) ->
                    FilterChip(
                        selected = fixes.look == look,
                        onClick = { onChange(fixes.copy(look = look)) },
                        label = { Text(stringResource(label)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.primaryContainer,
                            selectedLabelColor = colors.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

/** null keeps each mode's own look: Auto for pages, Board for whiteboards, Original for cards. */
private val LOOKS = listOf(
    null to R.string.capture_fix_look_usual,
    PageFilter.Auto to R.string.capture_fix_look_auto,
    PageFilter.Bright to R.string.capture_fix_look_bright,
    PageFilter.Gray to R.string.capture_fix_look_gray,
    PageFilter.Ink to R.string.capture_fix_look_ink,
    PageFilter.Original to R.string.capture_fix_look_original,
)

/** A fix, on or off: the whole card toggles, not just the switch. */
@Composable
private fun FixSwitch(title: Int, description: Int, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Switch },
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerHigh,
    ) {
        Row(
            Modifier
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(stringResource(description), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.surfaceContainerHighest,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }
    }
}
