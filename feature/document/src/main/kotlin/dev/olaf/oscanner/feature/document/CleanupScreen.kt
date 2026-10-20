package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.FrontHand
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.ZoomBox
import dev.olaf.oscanner.core.design.ApplyToPagesDialog
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.LocalOscannerExtras
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.model.Corner
import kotlin.math.min

/**
 * Remove things from a page. The hatch shows exactly what will go; holding Compare shows the
 * result. Nothing is changed until Apply, and Apply can be undone by coming back here.
 */
@Composable
fun CleanupRoute(viewModel: CleanupViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var comparing by remember { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var choosingPages by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val edits = state.edits

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    val leave = { if (state.changed) confirmDiscard = true else onDone() }
    BackHandler { leave() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = leave) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.document_back))
            }
            Text(stringResource(R.string.document_cleanup), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (state.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            TextButton(onClick = viewModel::undoStroke, enabled = viewModel.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.document_undo))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val before = state.before
            if (before == null || edits == null) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = colors.onSurfaceVariant)
            } else {
                // Pinch to zoom in on small things; one finger paints.
                ZoomBox(Modifier.fillMaxSize()) {
                    CleanupCanvas(state, comparing, onStroke = viewModel::addStroke, modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (edits != null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CleanupHint(state)
                // Two kinds of control, kept apart: switches that find things on their own, and a
                // brush you paint with.
                SectionLabel(stringResource(R.string.document_cleanup_auto))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AutoSwitch(
                        icon = Icons.Rounded.Gesture,
                        title = stringResource(R.string.document_remove_handwriting),
                        description = stringResource(R.string.document_remove_handwriting_description),
                        on = edits.removePenInk,
                        busy = state.findingPrint,
                        onToggle = { viewModel.setRemovePenInk(!edits.removePenInk) },
                    )
                    AutoSwitch(
                        icon = Icons.Rounded.FrontHand,
                        title = stringResource(R.string.document_remove_fingers),
                        description = stringResource(R.string.document_remove_fingers_description),
                        on = edits.removeFingers,
                        busy = false,
                        onToggle = { viewModel.setRemoveFingers(!edits.removeFingers) },
                    )
                    if (state.pages.size > 1 && (edits.removePenInk || edits.removeFingers)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.appliedTo > 0) {
                                Text(
                                    pluralStringResource(R.plurals.document_cleanup_applied, state.appliedTo, state.appliedTo),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            TextButton(onClick = { choosingPages = true }) {
                                Icon(Icons.Rounded.CopyAll, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.document_cleanup_other_pages))
                            }
                        }
                    }
                }
                SectionLabel(stringResource(R.string.document_brush))
                BrushPanel(
                    brush = state.brush,
                    size = state.size,
                    onBrush = viewModel::setBrush,
                    onSize = viewModel::setSize,
                )
            }
        }

        // Press and hold to see the page as it was; let go to see the result again.
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (comparing) colors.primaryContainer else colors.surfaceContainer,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 12.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        comparing = true
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        comparing = false
                    }
                },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Compare, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.document_hold_to_compare), style = MaterialTheme.typography.labelLarge)
            }
        }

        Dock(Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp).fillMaxWidth()) {
            DockAction(Icons.Rounded.Close, stringResource(R.string.document_cancel), leave)
            PrimaryPill(Icons.Rounded.Check, stringResource(R.string.document_apply), viewModel::save)
        }
    }

    if (choosingPages && edits != null) {
        val current = state.pages.indexOfFirst { it.id == state.page?.id }
        ApplyToPagesDialog(
            title = stringResource(R.string.document_cleanup_other_title),
            thumbs = state.pages.map { it.thumbPath ?: it.renderPath },
            current = current,
            onApply = { chosen ->
                viewModel.applyAutoTo(chosen.mapNotNull { state.pages.getOrNull(it)?.id }.toSet())
                choosingPages = false
            },
            onDismiss = { choosingPages = false },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = colors.surfaceContainer,
            title = { Text(stringResource(R.string.document_cleanup_discard)) },
            confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.document_apply)) } },
            dismissButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onDone()
                }) { Text(stringResource(R.string.document_discard), color = colors.onSurfaceVariant) }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp).semantics { heading() },
    )
}

/** One line under the page: what's happening, or what the brush does. */
@Composable
private fun CleanupHint(state: CleanupUiState) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        if (state.findingPrint) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            stringResource(
                when {
                    state.findingPrint -> R.string.document_finding_print
                    state.noPrintFound -> R.string.document_no_print_found
                    state.brush == CleanupBrush.Keep -> R.string.document_brush_keep_hint
                    else -> R.string.document_brush_erase_hint
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

/** An automatic removal, on or off: a card with a switch, unlike the brush controls below. */
@Composable
private fun AutoSwitch(
    icon: ImageVector,
    title: String,
    description: String,
    on: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        checked = on,
        onCheckedChange = { onToggle() },
        enabled = !busy,
        shape = MaterialTheme.shapes.large,
        color = if (on) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (on) colors.onPrimaryContainer else colors.onSurface,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Switch },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) colors.onPrimaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            } else {
                Switch(
                    checked = on,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onPrimary,
                        checkedTrackColor = colors.primary,
                        uncheckedThumbColor = colors.onSurfaceVariant,
                        uncheckedTrackColor = colors.surfaceContainerHigh,
                        uncheckedBorderColor = colors.outline,
                    ),
                )
            }
        }
    }
}

/**
 * The brush, in its own panel: what it does (Erase or Keep) as a two-way switch, and how wide
 * it is as three dots of that width, so the size is seen rather than read.
 */
@Composable
private fun BrushPanel(brush: CleanupBrush, size: BrushSize, onBrush: (CleanupBrush) -> Unit, onSize: (BrushSize) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                listOf(
                    Triple(CleanupBrush.Erase, Icons.Rounded.Brush, R.string.document_brush_erase),
                    Triple(CleanupBrush.Keep, Icons.Rounded.Shield, R.string.document_brush_keep),
                ).forEachIndexed { index, (value, icon, label) ->
                    SegmentedButton(
                        selected = brush == value,
                        onClick = { onBrush(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = colors.primaryContainer,
                            activeContentColor = colors.onPrimaryContainer,
                            inactiveContainerColor = colors.surfaceContainer,
                            inactiveContentColor = colors.onSurfaceVariant,
                            activeBorderColor = colors.outlineVariant,
                            inactiveBorderColor = colors.outlineVariant,
                        ),
                    ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, maxLines = 1) }
                }
            }
            Spacer(Modifier.width(8.dp))
            BrushSize.entries.forEach { option ->
                val label = stringResource(
                    when (option) {
                        BrushSize.Small -> R.string.document_brush_small
                        BrushSize.Medium -> R.string.document_brush_medium
                        BrushSize.Large -> R.string.document_brush_large
                    },
                )
                val chosen = option == size
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (chosen) colors.primaryContainer else Color.Transparent)
                        .border(if (chosen) 2.dp else 0.dp, if (chosen) colors.primary else Color.Transparent, CircleShape)
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSize(option) })
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    val dot = when (option) {
                        BrushSize.Small -> 6.dp
                        BrushSize.Medium -> 12.dp
                        BrushSize.Large -> 20.dp
                    }
                    Box(Modifier.size(dot).clip(CircleShape).background(if (chosen) colors.onPrimaryContainer else colors.onSurfaceVariant))
                }
            }
        }
    }
}

/** The page as it will be, removed areas tinted; a finger paints with the chosen brush. */
@Composable
private fun CleanupCanvas(state: CleanupUiState, comparing: Boolean, onStroke: (List<Corner>) -> Unit, modifier: Modifier = Modifier) {
    val before = remember(state.before) { state.before!!.asImageBitmap() }
    val after = remember(state.after) { state.after?.asImageBitmap() }
    val mask = remember(state.mask) { state.mask?.asImageBitmap() }
    val tint = MaterialTheme.colorScheme.primary
    val keepColor = LocalOscannerExtras.current.success
    val brush = MaterialTheme.colorScheme.primary
    val current = remember { mutableStateListOf<Corner>() }
    val width by rememberUpdatedState(state.size.width)
    val keep = state.brush == CleanupBrush.Keep

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scale = min(w / before.width, h / before.height)
        val shown = Rect(Offset((w - before.width * scale) / 2, (h - before.height * scale) / 2), Size(before.width * scale, before.height * scale))
        fun toPage(o: Offset) = Corner(((o.x - shown.left) / shown.width).coerceIn(0f, 1f), ((o.y - shown.top) / shown.height).coerceIn(0f, 1f))
        val dst = IntOffset(shown.left.toInt(), shown.top.toInt())
        val dstSize = IntSize(shown.width.toInt(), shown.height.toInt())

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) {
                    detectDragGestures(
                        onDragStart = {
                            current.clear()
                            current.add(toPage(it))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            current.add(toPage(change.position))
                        },
                        onDragEnd = {
                            onStroke(current.toList())
                            current.clear()
                        },
                        onDragCancel = { current.clear() },
                    )
                }
                .pointerInput(shown) { detectTapGestures { onStroke(listOf(toPage(it))) } },
        ) {
            drawImage(if (comparing || after == null) before else after, dstOffset = dst, dstSize = dstSize)
        }
        if (!comparing && mask != null) {
            // A light tint where paper was put back, so you can see what went: a flat color
            // kept only where the mask is opaque (offscreen layer, then DstIn).
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                drawRect(tint.copy(alpha = 0.22f), topLeft = shown.topLeft, size = shown.size)
                drawImage(mask, dstOffset = dst, dstSize = dstSize, blendMode = BlendMode.DstIn)
            }
        }
        if (current.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val path = Path().apply {
                    current.forEachIndexed { i, p ->
                        val x = shown.left + p.x * shown.width
                        val y = shown.top + p.y * shown.height
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(
                    path,
                    (if (keep) keepColor else brush).copy(alpha = 0.45f),
                    style = Stroke(width * shown.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
