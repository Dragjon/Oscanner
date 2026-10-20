package dev.olaf.oscanner.core.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The floating action deck that sits at thumb height on every main screen.
 * Holds at most one [PrimaryPill] and a few [DockAction]s.
 */
@Composable
fun Dock(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    val shadow = if (LocalOscannerExtras.current.dockShadow) Modifier.shadow(1.dp, shape) else Modifier
    Surface(
        modifier = modifier.then(shadow),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Spread out like a row, but no word is cut short while there's room for all of them:
        // the [PrimaryPill] keeps its whole word, and the other actions get what their words
        // need, sharing any shortfall in proportion rather than equally.
        Layout(
            content = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val isPill = measurables.map { it.layoutId == PRIMARY }
            val needs = measurables.mapIndexed { i, m -> if (isPill[i]) 0 else m.maxIntrinsicWidth(constraints.maxHeight) }
            val pills = isPill.count { it }
            val pillRoom = if (pills == 0) 0 else (width - needs.sum()) / pills
            val pill = measurables.indices.filter { isPill[it] }.associateWith { i ->
                val m = measurables[i]
                val most = max(pillRoom, m.minIntrinsicWidth(constraints.maxHeight)).coerceAtMost(width)
                // Roomy when there's space; down to a snug fit around its word when there isn't.
                m.measure(loose.copy(minWidth = min(PILL_WIDTH.roundToPx(), most), maxWidth = most))
            }
            val left = (width - pill.values.sumOf { it.width }).coerceAtLeast(0)
            val scale = if (needs.sum() > left) left.toFloat() / needs.sum() else 1f
            val placeables = measurables.mapIndexed { i, m -> pill[i] ?: m.measure(loose.copy(maxWidth = (needs[i] * scale).toInt())) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            val free = (width - placeables.sumOf { it.width }).coerceAtLeast(0)
            layout(width, height) {
                if (placeables.size == 1) {
                    placeables[0].place((width - placeables[0].width) / 2, (height - placeables[0].height) / 2)
                    return@layout
                }
                var x = 0
                placeables.forEachIndexed { i, p ->
                    p.place(x, (height - p.height) / 2)
                    x += p.width + if (i < placeables.lastIndex) free / placeables.lastIndex else 0
                }
            }
        }
    }
}

private const val PRIMARY = "primary"

private val PILL_WIDTH = 140.dp


/**
 * The dock's one-word labels grow with the system font size up to [MAX_DOCK_SCALE], then stop,
 * so four of them still fit side by side at 200%. Like system navigation bars; TalkBack reads
 * the same words either way.
 */
@Composable
private fun cappedScale(style: TextStyle): TextStyle {
    val scale = LocalDensity.current.fontScale
    if (scale <= MAX_DOCK_SCALE) return style
    val factor = MAX_DOCK_SCALE / scale
    return style.copy(fontSize = style.fontSize * factor, lineHeight = style.lineHeight * factor)
}

private const val MAX_DOCK_SCALE = 1.3f

/** A neutral action: icon above a word. Never colored, because it is not the primary action. */
@Composable
fun DockAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** For a switch: lit while it's on, so its word can stay the same. */
    on: Boolean? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = 56.dp)
            .pressScale(interaction)
            .then(if (on != null) Modifier.semantics { toggleableState = ToggleableState(on) } else Modifier),
        shape = MaterialTheme.shapes.large,
        color = if (on == true) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (on == true) colors.onSecondaryContainer else colors.onSurface,
        interactionSource = interaction,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = cappedScale(MaterialTheme.typography.labelMedium), maxLines = 1)
        }
    }
}

/** The one orange thing on a screen: the action the screen exists for. */
@Composable
fun PrimaryPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .layoutId(PRIMARY)
            .height(56.dp)
            .pressScale(interaction),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = cappedScale(MaterialTheme.typography.labelLarge), maxLines = 1)
        }
    }
}

/** Search as a card-shaped field with a plain-language placeholder. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Closing the keyboard (back gesture, or the keyboard's own button) also ends the search
    // edit, so no cursor is left blinking in the field.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var keyboardWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(keyboardOpen) {
        if (keyboardWasOpen && !keyboardOpen && focused) focusManager.clearFocus()
        keyboardWasOpen = keyboardOpen
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        decorationBox = { inner ->
            Surface(shape = MaterialTheme.shapes.medium, color = colors.surfaceContainer) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.design_clear_search),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** A row in a bottom sheet: icon, name, and one line that says what it does. */
@Composable
fun SheetAction(
    icon: ImageVector,
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        interactionSource = interaction,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}
