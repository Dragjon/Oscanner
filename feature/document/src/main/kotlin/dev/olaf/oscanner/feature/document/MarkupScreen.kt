package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.FormatClear
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.model.TextMark
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.data.Signature
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import kotlin.math.max
import kotlin.math.min

/** Sign, write and highlight on one page. Marks are kept apart from the scan and can be erased. */
@Composable
fun MarkupRoute(viewModel: MarkupViewModel, onDone: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signatures by viewModel.signatures.signatures.collectAsStateWithLifecycle()
    var signing by rememberSaveable { mutableStateOf(false) }
    /** The text dialog: a new text at a point, or an existing one by index. */
    var newTextAt by remember { mutableStateOf<Corner?>(null) }
    var editingText by rememberSaveable { mutableStateOf(-1) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(state.saved) { if (state.saved) onDone() }
    val leave = { if (state.changed) confirmDiscard = true else onDone() }
    BackHandler(enabled = state.placement == null) { leave() }
    BackHandler(enabled = state.placement != null) { viewModel.cancelPlacement() }

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
            Text(stringResource(R.string.document_markup), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::undo, enabled = viewModel.canUndo && state.placement == null) {
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
            val base = state.base
            if (base == null) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = colors.onSurfaceVariant)
            } else {
                MarkupCanvas(
                    base = remember(base) { base.asImageBitmap() },
                    state = state,
                    onStroke = viewModel::addStroke,
                    onErase = viewModel::eraseAt,
                    onMoveSignature = viewModel::moveSignature,
                    onTextTap = { at ->
                        val hit = viewModel.textAt(at, base.width.toFloat(), base.height.toFloat())
                        if (hit >= 0) editingText = hit else newTextAt = at
                    },
                    onTextDragStart = { at ->
                        viewModel.textAt(at, base.width.toFloat(), base.height.toFloat()).also { if (it >= 0) viewModel.startMovingText() }
                    },
                    onTextDrag = viewModel::moveText,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val placement = state.placement
        if (placement != null) {
            // Positioning a signature: size, then put it down.
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(stringResource(R.string.document_signature_place_hint), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Slider(
                    value = placement.width,
                    onValueChange = viewModel::resizeSignature,
                    valueRange = 0.1f..0.9f,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.surfaceContainerHighest),
                )
            }
            Dock(Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp).fillMaxWidth()) {
                DockAction(Icons.Rounded.Close, stringResource(R.string.document_cancel), viewModel::cancelPlacement)
                PrimaryPill(Icons.Rounded.Check, stringResource(R.string.document_signature_place), {
                    val b = state.base ?: return@PrimaryPill
                    viewModel.confirmPlacement(b.width.toFloat() / b.height)
                })
            }
        } else {
            ToolRow(state, onTool = viewModel::setTool, onColor = viewModel::setColor)
            Dock(Modifier.align(Alignment.CenterHorizontally).navigationBarsPadding().padding(16.dp).widthIn(max = 480.dp).fillMaxWidth()) {
                DockAction(Icons.Rounded.Draw, stringResource(R.string.document_sign), { signing = true })
                PrimaryPill(Icons.Rounded.Check, stringResource(R.string.document_ok), viewModel::save)
            }
        }
    }

    if (signing) {
        SignatureDialog(
            saved = signatures,
            onUse = {
                signing = false
                viewModel.place(it)
            },
            onDelete = viewModel::deleteSignature,
            onDrawn = { strokes, aspect, keep ->
                signing = false
                viewModel.saveSignature(strokes, aspect, keep)
            },
            onDismiss = { signing = false },
        )
    }
    newTextAt?.let { at ->
        TextDialog(
            initial = null,
            onDone = { text, size, stamp ->
                newTextAt = null
                viewModel.addText(text, at, size, stamp)
            },
            onDelete = null,
            onDismiss = { newTextAt = null },
        )
    }
    state.texts.getOrNull(editingText)?.let { mark ->
        TextDialog(
            initial = mark,
            onDone = { text, size, stamp ->
                viewModel.updateText(editingText, text, size, stamp)
                editingText = -1
            },
            onDelete = {
                viewModel.removeText(editingText)
                editingText = -1
            },
            onDismiss = { editingText = -1 },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            containerColor = colors.surfaceContainer,
            title = { Text(stringResource(R.string.document_markup_discard)) },
            confirmButton = { TextButton(onClick = viewModel::save) { Text(stringResource(R.string.document_ok)) } },
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
private fun ToolRow(state: MarkupUiState, onTool: (MarkupTool) -> Unit, onColor: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolChip(Icons.Rounded.Edit, stringResource(R.string.document_tool_pen), state.tool == MarkupTool.Pen) { onTool(MarkupTool.Pen) }
        ToolChip(Icons.Rounded.Highlight, stringResource(R.string.document_tool_highlighter), state.tool == MarkupTool.Highlighter) { onTool(MarkupTool.Highlighter) }
        ToolChip(Icons.Rounded.TextFields, stringResource(R.string.document_tool_text), state.tool == MarkupTool.Text) { onTool(MarkupTool.Text) }
        ToolChip(Icons.Rounded.FormatClear, stringResource(R.string.document_tool_eraser), state.tool == MarkupTool.Eraser) { onTool(MarkupTool.Eraser) }
        Spacer(Modifier.width(4.dp))
        val names = listOf(R.string.document_color_black, R.string.document_color_blue, R.string.document_color_red)
        INK_COLORS.forEachIndexed { i, color ->
            val chosen = (state.tool == MarkupTool.Pen || state.tool == MarkupTool.Text) && state.color == color
            val name = stringResource(names[i])
            Box(
                Modifier
                    .size(40.dp)
                    .semantics {
                        role = Role.RadioButton
                        selected = chosen
                        contentDescription = name
                    }
                    .border(if (chosen) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(6.dp)
                    .background(Color(color), CircleShape)
                    .pointerInput(color) { detectTapGestures { onColor(color) } },
            )
        }
    }
}

@Composable
private fun ToolChip(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primary else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .height(40.dp)
            .semantics {
                role = Role.RadioButton
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

/** The page with its marks; a finger draws, erases, or drags the signature being placed. */
@Composable
private fun MarkupCanvas(
    base: ImageBitmap,
    state: MarkupUiState,
    onStroke: (List<Corner>) -> Unit,
    onErase: (Corner, Float, Float) -> Unit,
    onMoveSignature: (Float, Float) -> Unit,
    onTextTap: (Corner) -> Unit,
    /** Returns the index of the text under the finger, or -1. */
    onTextDragStart: (Corner) -> Int,
    onTextDrag: (index: Int, dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember { mutableStateListOf<Corner>() }
    var movingText by remember { mutableStateOf(-1) }
    val tool by rememberUpdatedState(state.tool)
    val placing by rememberUpdatedState(state.placement != null)
    val pageAspect = base.width.toFloat() / base.height

    BoxWithConstraints(modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val scale = min(w / base.width, h / base.height)
        val shown = Rect(Offset((w - base.width * scale) / 2, (h - base.height * scale) / 2), Size(base.width * scale, base.height * scale))
        fun toPage(o: Offset) = Corner(((o.x - shown.left) / shown.width).coerceIn(0f, 1f), ((o.y - shown.top) / shown.height).coerceIn(0f, 1f))

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(shown) {
                    detectDragGestures(
                        onDragStart = { start ->
                            current.clear()
                            if (!placing && tool == MarkupTool.Text) {
                                movingText = onTextDragStart(toPage(start))
                            } else if (!placing) {
                                if (tool == MarkupTool.Eraser) onErase(toPage(start), 0.02f, pageAspect) else current.add(toPage(start))
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            when {
                                placing -> onMoveSignature(drag.x / shown.width, drag.y / shown.height)
                                tool == MarkupTool.Text -> if (movingText >= 0) onTextDrag(movingText, drag.x / shown.width, drag.y / shown.height)
                                tool == MarkupTool.Eraser -> onErase(toPage(change.position), 0.02f, pageAspect)
                                else -> current.add(toPage(change.position))
                            }
                        },
                        onDragEnd = {
                            if (!placing && tool != MarkupTool.Eraser && tool != MarkupTool.Text) onStroke(current.toList())
                            current.clear()
                            movingText = -1
                        },
                        onDragCancel = {
                            current.clear()
                            movingText = -1
                        },
                    )
                }
                .pointerInput(shown) {
                    // A tap makes a dot with the pen, or erases what's under it.
                    detectTapGestures { at ->
                        if (placing) return@detectTapGestures
                        if (tool == MarkupTool.Text) return@detectTapGestures onTextTap(toPage(at))
                        if (tool == MarkupTool.Eraser) onErase(toPage(at), 0.02f, pageAspect) else onStroke(listOf(toPage(at)))
                    }
                },
        ) {
            drawImage(base, dstOffset = IntOffset(shown.left.toInt(), shown.top.toInt()), dstSize = IntSize(shown.width.toInt(), shown.height.toInt()))
            state.marks.forEach { drawInk(it, shown) }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(shown.left, shown.top)
                PageRenderer.drawTexts(native, state.texts, shown.width, shown.height)
                native.restore()
            }
            if (state.tool == MarkupTool.Text) {
                // Dashed frames say "tap to change, drag to move".
                state.texts.forEach { t ->
                    val (hw, hh) = PageRenderer.textHalfSize(t, shown.width).let { if (t.angle % 180 != 0) it.second to it.first else it }
                    val pad = 4.dp.toPx()
                    drawRect(
                        color = Color(0xFF7D7369),
                        topLeft = Offset(shown.left + t.x * shown.width - hw - pad, shown.top + t.y * shown.height - hh - pad),
                        size = Size((hw + pad) * 2, (hh + pad) * 2),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))),
                    )
                }
            }
            if (current.isNotEmpty()) {
                val highlighter = state.tool == MarkupTool.Highlighter
                drawInk(
                    InkMark(current.toList(), if (highlighter) HIGHLIGHTER_COLOR else state.color, if (highlighter) 0.03f else 0.004f, highlighter),
                    shown,
                )
            }
            state.placement?.let { placement ->
                val strokes = MarkupViewModel.signatureStrokes(placement, pageAspect)
                strokes.forEach { drawInk(InkMark(it, state.color, 0.0035f), shown) }
                // A dashed frame says "this can be moved".
                val xs = strokes.flatten().map { it.x }
                val ys = strokes.flatten().map { it.y }
                if (xs.isNotEmpty()) {
                    val pad = 8.dp.toPx()
                    drawRect(
                        color = Color(0xFF7D7369),
                        topLeft = Offset(shown.left + xs.min() * shown.width - pad, shown.top + ys.min() * shown.height - pad),
                        size = Size((xs.max() - xs.min()) * shown.width + pad * 2, (ys.max() - ys.min()) * shown.height + pad * 2),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawInk(mark: InkMark, shown: Rect) {
    val color = Color(mark.color)
    val width = max(1f, mark.width * shown.width)
    val blend = if (mark.highlighter) BlendMode.Multiply else BlendMode.SrcOver
    if (mark.points.size == 1) {
        val p = mark.points[0]
        drawCircle(color, width / 2, Offset(shown.left + p.x * shown.width, shown.top + p.y * shown.height), blendMode = blend)
        return
    }
    val path = Path().apply {
        mark.points.forEachIndexed { i, p ->
            val x = shown.left + p.x * shown.width
            val y = shown.top + p.y * shown.height
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
}

/**
 * Type text, or pick today's date or a stamp word. [initial] is the text being changed, or
 * null for new text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextDialog(
    initial: TextMark?,
    onDone: (text: String, size: TextSize, stamp: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var size by rememberSaveable { mutableStateOf(TextSize.entries.minBy { kotlin.math.abs(it.size - (initial?.size ?: TextSize.Medium.size)) }) }
    var stamp by rememberSaveable { mutableStateOf(initial?.stamp ?: false) }
    val today = remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date()) }
    val words = listOf(
        stringResource(R.string.document_stamp_paid),
        stringResource(R.string.document_stamp_received),
        stringResource(R.string.document_stamp_approved),
        stringResource(R.string.document_stamp_copy),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(if (initial == null) R.string.document_text_add else R.string.document_text_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    placeholder = { Text(stringResource(R.string.document_text_hint)) },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.onSurfaceVariant,
                        unfocusedBorderColor = colors.outlineVariant,
                        cursorColor = colors.primary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickChip(stringResource(R.string.document_text_today)) {
                        text = today
                        stamp = false
                    }
                    words.forEach { word ->
                        QuickChip(word) {
                            text = word
                            stamp = true
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextSize.entries.forEach { option ->
                        val label = stringResource(
                            when (option) {
                                TextSize.Small -> R.string.document_text_small
                                TextSize.Medium -> R.string.document_text_medium
                                TextSize.Large -> R.string.document_text_large
                            },
                        )
                        ToolChip(null, label, size == option) { size = option }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = stamp, onCheckedChange = { stamp = it }, colors = CheckboxDefaults.colors(checkedColor = colors.primary, checkmarkColor = colors.onPrimary))
                    Text(stringResource(R.string.document_text_stamp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text, size, stamp) }, enabled = text.isNotBlank()) {
                Text(stringResource(if (initial == null) R.string.document_text_place else R.string.document_ok))
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.document_text_delete), color = colors.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) }
            }
        },
    )
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
        modifier = Modifier.height(36.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Draw a signature on a white pad, or pick one kept from before. */
@Composable
private fun SignatureDialog(
    saved: List<Signature>,
    onUse: (Signature) -> Unit,
    onDelete: (String) -> Unit,
    onDrawn: (strokes: List<List<Corner>>, aspect: Float, keep: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var keep by rememberSaveable { mutableStateOf(true) }
    val colors = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surfaceContainer,
        title = { Text(stringResource(R.string.document_signature_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (saved.isNotEmpty()) {
                    Text(stringResource(R.string.document_signature_saved), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        saved.forEach { signature ->
                            Box {
                                Surface(
                                    onClick = { onUse(signature) },
                                    shape = MaterialTheme.shapes.small,
                                    color = Color.White,
                                    border = BorderStroke(1.dp, colors.outlineVariant),
                                    modifier = Modifier.size(width = 120.dp, height = 56.dp),
                                ) {
                                    Canvas(Modifier.padding(8.dp)) {
                                        val box = fitBox(size, signature.aspect)
                                        signature.strokes.forEach { s ->
                                            drawInk(InkMark(s, INK_COLORS.first(), 0.025f), box)
                                        }
                                    }
                                }
                                IconButton(onClick = { onDelete(signature.id) }, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.document_signature_forget), modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                    Text(stringResource(R.string.document_signature_new), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(Color.White, MaterialTheme.shapes.small)
                        .border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    current.clear()
                                    current.add(it)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current.add(change.position)
                                },
                                onDragEnd = {
                                    strokes.add(current.toList())
                                    current.clear()
                                },
                            )
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        (strokes + listOf(current.toList())).filter { it.size > 1 }.forEach { s ->
                            val path = Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                            drawPath(path, Color(INK_COLORS.first()), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        // The line people sign on.
                        drawLine(Color(0xFFB9B2A8), Offset(16.dp.toPx(), size.height * 0.75f), Offset(size.width - 16.dp.toPx(), size.height * 0.75f), 1.dp.toPx())
                    }
                    if (strokes.isEmpty()) {
                        Text(
                            stringResource(R.string.document_signature_pad_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6F625A),
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = keep, onCheckedChange = { keep = it }, colors = CheckboxDefaults.colors(checkedColor = colors.primary, checkmarkColor = colors.onPrimary))
                    Text(stringResource(R.string.document_signature_keep), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.document_clear_choice)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val (normalized, aspect) = normalize(strokes)
                    onDrawn(normalized, aspect, keep)
                },
                enabled = strokes.isNotEmpty(),
            ) { Text(stringResource(R.string.document_signature_use)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.document_cancel), color = colors.onSurfaceVariant) } },
    )
}

/** Strokes scaled into their own bounding box (0…1 each way), and that box's width ÷ height. */
private fun normalize(strokes: List<List<Offset>>): Pair<List<List<Corner>>, Float> {
    val all = strokes.flatten()
    val left = all.minOf { it.x }
    val top = all.minOf { it.y }
    val w = max(1f, all.maxOf { it.x } - left)
    val h = max(1f, all.maxOf { it.y } - top)
    return strokes.map { s -> s.map { Corner((it.x - left) / w, (it.y - top) / h) } } to (w / h)
}

/** The largest box of [aspect] centered in [size]. */
private fun fitBox(size: Size, aspect: Float): Rect {
    val w = min(size.width, size.height * aspect)
    val h = w / aspect
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}
