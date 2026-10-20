package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.FrontHand
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                CleanupCanvas(state, comparing, onStroke = viewModel::addStroke, modifier = Modifier.fillMaxSize())
            }
        }

        if (edits != null) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Toggle(Icons.Rounded.Edit, stringResource(R.string.document_remove_handwriting), edits.removePenInk) { viewModel.setRemovePenInk(!edits.removePenInk) }
                    Toggle(Icons.Rounded.FrontHand, stringResource(R.string.document_remove_fingers), edits.removeFingers) { viewModel.setRemoveFingers(!edits.removeFingers) }
                }
                Text(
                    stringResource(if (edits.removePenInk) R.string.document_remove_handwriting_on else R.string.document_cleanup_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Toggle(Icons.Rounded.Brush, stringResource(R.string.document_brush_erase), state.brush == CleanupBrush.Erase) { viewModel.setBrush(CleanupBrush.Erase) }
                    Toggle(Icons.Rounded.Shield, stringResource(R.string.document_brush_keep), state.brush == CleanupBrush.Keep) { viewModel.setBrush(CleanupBrush.Keep) }
                    Spacer(Modifier.width(4.dp))
                    BrushSize.entries.forEach { size ->
                        val label = stringResource(
                            when (size) {
                                BrushSize.Small -> R.string.document_brush_small
                                BrushSize.Medium -> R.string.document_brush_medium
                                BrushSize.Large -> R.string.document_brush_large
                            },
                        )
                        Toggle(null, label, state.size == size) { viewModel.setSize(size) }
                    }
                }
            }
        }

        // Press and hold to see the result; let go to see what's marked again.
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
private fun Toggle(icon: ImageVector?, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primary else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .height(40.dp)
            .semantics {
                role = Role.Checkbox
                this.selected = selected
            },
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The page with the hatch over what will be removed; a finger paints with the chosen brush. */
@Composable
private fun CleanupCanvas(state: CleanupUiState, comparing: Boolean, onStroke: (List<Corner>) -> Unit, modifier: Modifier = Modifier) {
    val before = remember(state.before) { state.before!!.asImageBitmap() }
    val after = remember(state.after) { state.after?.asImageBitmap() }
    val mask = remember(state.mask) { state.mask?.asImageBitmap() }
    val hatch = LocalOscannerExtras.current.maskHatch
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
            drawImage(if (comparing && after != null) after else before, dstOffset = dst, dstSize = dstSize)
        }
        if (!comparing && mask != null) {
            // Diagonal lines kept only where the mask is opaque: an offscreen layer, then DstIn.
            Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
                val step = 8.dp.toPx()
                // The mask only trims inside the page, so the lines must not leave it.
                clipRect(shown.left, shown.top, shown.right, shown.bottom) {
                    var x = shown.left - shown.height
                    while (x < shown.right) {
                        drawLine(hatch.copy(alpha = 0.9f), Offset(x, shown.bottom), Offset(x + shown.height, shown.top), 2.dp.toPx())
                        x += step
                    }
                }
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
