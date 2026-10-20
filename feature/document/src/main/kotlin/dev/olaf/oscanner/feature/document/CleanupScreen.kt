package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.FrontHand
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.LineWeight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.design.ApplyToPagesDialog
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.EditorToolbar
import dev.olaf.oscanner.core.design.EditorTopBar
import dev.olaf.oscanner.core.design.LocalOscannerExtras
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.ToolbarButton
import dev.olaf.oscanner.core.design.ZoomBox
import dev.olaf.oscanner.core.design.detectDragsFromTouch
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
    val holdLabel = stringResource(R.string.document_hold_to_compare)

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    val leave = { if (state.changed) confirmDiscard = true else onDone() }
    BackHandler { leave() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        EditorTopBar(
            title = stringResource(R.string.document_cleanup),
            closeLabel = stringResource(R.string.document_cancel),
            onClose = leave,
            doneLabel = stringResource(R.string.document_apply),
            onDone = viewModel::save,
        ) {
            if (state.working) CircularProgressIndicator(Modifier.padding(horizontal = 8.dp).size(18.dp), strokeWidth = 2.dp, color = colors.onSurfaceVariant)
            // Press and hold to see the page as it was; let go to see the result again.
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (comparing) colors.primaryContainer else Color.Transparent)
                    .semantics { contentDescription = holdLabel }
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
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Compare, contentDescription = null) }
            IconButton(onClick = viewModel::undoStroke, enabled = viewModel.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.document_undo))
            }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
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
                CleanupHint(state, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
        }

        if (edits != null) {
            // One row: the switches that find things on their own, then the brush you paint with.
            EditorToolbar(Modifier.navigationBarsPadding()) {
                ToolbarButton(
                    Icons.Rounded.Gesture,
                    stringResource(R.string.document_remove_handwriting),
                    selected = edits.removePenInk,
                    onClick = { viewModel.setRemovePenInk(!edits.removePenInk) },
                )
                ToolbarButton(
                    Icons.Rounded.FrontHand,
                    stringResource(R.string.document_remove_fingers),
                    selected = edits.removeFingers,
                    onClick = { viewModel.setRemoveFingers(!edits.removeFingers) },
                )
                if (state.pages.size > 1 && (edits.removePenInk || edits.removeFingers)) {
                    ToolbarButton(Icons.Rounded.CopyAll, stringResource(R.string.document_cleanup_other_pages_short), selected = null, onClick = { choosingPages = true })
                }
                VerticalDivider(Modifier.height(36.dp).padding(horizontal = 6.dp), color = colors.outlineVariant)
                ToolbarButton(
                    Icons.Rounded.Brush,
                    stringResource(R.string.document_brush_erase),
                    selected = state.brush == CleanupBrush.Erase,
                    onClick = { viewModel.setBrush(CleanupBrush.Erase) },
                )
                ToolbarButton(
                    Icons.Rounded.Shield,
                    stringResource(R.string.document_brush_keep),
                    selected = state.brush == CleanupBrush.Keep,
                    onClick = { viewModel.setBrush(CleanupBrush.Keep) },
                )
                // The brush width, one tap to the next: thin, medium, wide.
                val sizes = BrushSize.entries
                ToolbarButton(
                    Icons.Rounded.LineWeight,
                    stringResource(
                        when (state.size) {
                            BrushSize.Small -> R.string.document_brush_small
                            BrushSize.Medium -> R.string.document_brush_medium
                            BrushSize.Large -> R.string.document_brush_large
                        },
                    ),
                    selected = null,
                    onClick = { viewModel.setSize(sizes[(state.size.ordinal + 1) % sizes.size]) },
                )
            }
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

/** A word over the bottom of the page, when there's something to say. */
@Composable
private fun CleanupHint(state: CleanupUiState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val text = when {
        state.findingPrint -> R.string.document_finding_print
        state.noPrintFound -> R.string.document_no_print_found
        state.appliedTo > 0 -> null
        else -> return
    }
    Surface(
        color = colors.inverseSurface.copy(alpha = 0.9f),
        contentColor = colors.inverseOnSurface,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.findingPrint) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.inverseOnSurface)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (text != null) stringResource(text) else pluralStringResource(R.plurals.document_cleanup_applied, state.appliedTo, state.appliedTo),
                style = MaterialTheme.typography.bodyMedium,
            )
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
                    detectDragsFromTouch(
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
