package dev.olaf.oscanner.feature.document

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatClear
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.olaf.oscanner.core.data.Signature
import dev.olaf.oscanner.core.design.Dock
import dev.olaf.oscanner.core.design.DockAction
import dev.olaf.oscanner.core.design.EditorToolbar
import dev.olaf.oscanner.core.design.EditorTopBar
import dev.olaf.oscanner.core.design.PrimaryPill
import dev.olaf.oscanner.core.design.ToolbarButton
import dev.olaf.oscanner.core.design.ZoomBox
import dev.olaf.oscanner.core.design.detectDragsFromTouch
import dev.olaf.oscanner.core.imaging.InkPath
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.LineStyle
import dev.olaf.oscanner.core.model.TextFont
import dev.olaf.oscanner.core.model.TextMark
import dev.olaf.oscanner.core.model.WordBox
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
    /** A read word being retyped. */
    var retyping by remember { mutableStateOf<WordBox?>(null) }
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
        EditorTopBar(
            title = stringResource(R.string.document_markup),
            closeLabel = stringResource(R.string.document_cancel),
            onClose = leave,
            doneLabel = stringResource(R.string.document_ok),
            onDone = viewModel::save,
            doneEnabled = state.placement == null,
        ) {
            IconButton(onClick = viewModel::undo, enabled = viewModel.canUndo && state.placement == null) {
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
            val base = state.base
            if (base == null) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = colors.onSurfaceVariant)
            } else {
                // Pinch to zoom in for small writing; one finger draws.
                ZoomBox(Modifier.fillMaxSize()) {
                    MarkupCanvas(
                        base = remember(base) { base.asImageBitmap() },
                        state = state,
                        onStroke = viewModel::addStroke,
                        onErase = { at, aspect -> viewModel.eraseAt(at, state.eraserSize, aspect) },
                        onMoveSignature = viewModel::moveSignature,
                        onTextTap = { at ->
                            // A tap selects a text (the options then change its style); a second tap
                            // on it changes its words; a tap on blank paper adds new text.
                            val hit = viewModel.textAt(at, base.width.toFloat(), base.height.toFloat())
                            when {
                                hit < 0 -> viewModel.wordAt(at)?.let { retyping = it } ?: run { newTextAt = at }
                                hit == state.selectedText -> editingText = hit
                                else -> viewModel.selectText(hit)
                            }
                        },
                        onTextDragStart = { at ->
                            viewModel.textAt(at, base.width.toFloat(), base.height.toFloat()).also { if (it >= 0) viewModel.startMovingText() }
                        },
                        onTextDrag = viewModel::moveText,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (state.tool == MarkupTool.Text && state.placement == null) {
                    Surface(
                        color = colors.inverseSurface.copy(alpha = 0.9f),
                        contentColor = colors.inverseOnSurface,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Text(
                            stringResource(if (state.selectedText >= 0) R.string.document_text_selected_hint else R.string.document_text_tool_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        val placement = state.placement
        if (placement != null) {
            // Positioning a signature: size, then put it down.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.document_option_size), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                Slider(
                    value = placement.width,
                    onValueChange = viewModel::resizeSignature,
                    valueRange = 0.1f..0.9f,
                    colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary, inactiveTrackColor = colors.surfaceContainerHighest),
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            EditorToolbar(Modifier.navigationBarsPadding()) {
                ToolbarButton(Icons.Rounded.Close, stringResource(R.string.document_cancel), selected = null, onClick = viewModel::cancelPlacement)
                ToolbarButton(Icons.Rounded.Check, stringResource(R.string.document_signature_place), selected = null, onClick = {
                    val b = state.base ?: return@ToolbarButton
                    viewModel.confirmPlacement(b.width.toFloat() / b.height)
                })
            }
        } else {
            ToolOptions(
                state = state,
                onPen = viewModel::setPen,
                onHighlighter = viewModel::setHighlighter,
                onText = viewModel::setTextStyle,
                onEraser = viewModel::setEraserSize,
            )
            EditorToolbar(Modifier.navigationBarsPadding()) {
                ToolbarButton(Icons.Rounded.Edit, stringResource(R.string.document_tool_pen), state.tool == MarkupTool.Pen, { viewModel.setTool(MarkupTool.Pen) })
                ToolbarButton(Icons.Rounded.Highlight, stringResource(R.string.document_tool_highlighter), state.tool == MarkupTool.Highlighter, { viewModel.setTool(MarkupTool.Highlighter) })
                ToolbarButton(Icons.Rounded.TextFields, stringResource(R.string.document_tool_text), state.tool == MarkupTool.Text, { viewModel.setTool(MarkupTool.Text) })
                ToolbarButton(Icons.Rounded.FormatClear, stringResource(R.string.document_tool_eraser), state.tool == MarkupTool.Eraser, { viewModel.setTool(MarkupTool.Eraser) })
                ToolbarButton(Icons.Rounded.Draw, stringResource(R.string.document_sign), selected = null, onClick = { signing = true })
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
    retyping?.let { word ->
        TextDialog(
            initial = TextMark(word.text, 0f, 0f, 0f, 0),
            retype = true,
            onDone = { text, _ ->
                retyping = null
                viewModel.retype(word, text)
            },
            onDelete = null,
            onDismiss = { retyping = null },
        )
    }
    newTextAt?.let { at ->
        TextDialog(
            initial = null,
            onDone = { text, stamp ->
                newTextAt = null
                viewModel.addText(text, at, stamp)
            },
            onDelete = null,
            onDismiss = { newTextAt = null },
        )
    }
    state.texts.getOrNull(editingText)?.let { mark ->
        TextDialog(
            initial = mark,
            onDone = { text, stamp ->
                viewModel.updateText(editingText, text, stamp)
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

/**
 * The chosen tool's own settings in one row over the tools: colors as swatches, sizes as dots of
 * that size, line types and fonts as small previews of themselves. Scrolls sideways when long.
 */
@Composable
private fun ToolOptions(
    state: MarkupUiState,
    onPen: (PenSettings) -> Unit,
    onHighlighter: (HighlighterSettings) -> Unit,
    onText: (TextSettings) -> Unit,
    onEraser: (Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        OptionRow {
            when (state.tool) {
                MarkupTool.Pen -> {
                    val pen = state.pen
                    Swatches(PEN_COLORS, pen.color) { onPen(pen.copy(color = it)) }
                    Gap()
                    SizeDots(PEN_WIDTHS, pen.width, R.string.document_option_width) { onPen(pen.copy(width = it)) }
                    Gap()
                    LineStyle.entries.forEach { style -> LineStyleChip(style, pen.style == style) { onPen(pen.copy(style = style)) } }
                }
                MarkupTool.Highlighter -> {
                    val h = state.highlighter
                    Swatches(HIGHLIGHTER_COLORS, h.color) { onHighlighter(h.copy(color = it)) }
                    Gap()
                    SizeDots(HIGHLIGHTER_WIDTHS, h.width, R.string.document_option_width) { onHighlighter(h.copy(width = it)) }
                }
                MarkupTool.Text -> {
                    val t = state.text
                    Swatches(PEN_COLORS, t.color) { onText(t.copy(color = it)) }
                    Gap()
                    SizeDots(TEXT_SIZES, t.size, R.string.document_option_size, letters = true) { onText(t.copy(size = it)) }
                    Gap()
                    TextFont.entries.forEach { font -> FontChip(font, t.font == font) { onText(t.copy(font = font)) } }
                    BoldChip(t.bold) { onText(t.copy(bold = !t.bold)) }
                    Gap()
                    TEXT_BACKGROUNDS.forEach { fill -> BackgroundChip(fill, t.background == fill) { onText(t.copy(background = fill)) } }
                }
                MarkupTool.Eraser -> SizeDots(ERASER_SIZES, state.eraserSize, R.string.document_option_size) { onEraser(it) }
            }
        }
    }
}

/** A thin line between groups of options. */
@Composable
private fun Gap() {
    VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun OptionRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

/** Colors as round swatches; the chosen one ringed and ticked. */
@Composable
private fun Swatches(options: List<Int>, chosen: Int, onPick: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    run {
        options.forEach { argb ->
            val selected = argb == chosen
            val swatch = Color(argb)
            val name = colorName(argb)
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(argb) })
                    .semantics { contentDescription = name }
                    .border(if (selected) 2.dp else 0.dp, if (selected) colors.onSurface else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .background(swatch, CircleShape)
                    .border(1.dp, colors.outline.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = if (swatch.luminance() > 0.5f) Color(0xFF1A1A1A) else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Three sizes, each a dot (or, for text, an A) drawn at that size. */
@Composable
private fun SizeDots(options: List<Float>, chosen: Float, label: Int, letters: Boolean = false, onPick: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(label)
    options.forEachIndexed { index, value ->
        val selected = kotlin.math.abs(value - chosen) < 1e-5f
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (selected) colors.primaryContainer else Color.Transparent)
                .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(value) })
                .semantics { contentDescription = "$name ${index + 1}" },
            contentAlignment = Alignment.Center,
        ) {
            val ink = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
            if (letters) {
                Text("A", color = ink, style = MaterialTheme.typography.labelLarge.copy(fontSize = (11 + index * 5).sp))
            } else {
                val dot = (6 + index * 6).dp
                Box(Modifier.size(dot).clip(CircleShape).background(ink))
            }
        }
    }
}

/** Solid, dashed or dotted, drawn as a short line. */
@Composable
private fun LineStyleChip(style: LineStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(
        when (style) {
            LineStyle.Solid -> R.string.document_line_solid
            LineStyle.Dashed -> R.string.document_line_dashed
            LineStyle.Dotted -> R.string.document_line_dotted
        },
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        modifier = Modifier.size(width = 56.dp, height = 40.dp).semantics { contentDescription = name },
    ) {
        Canvas(Modifier.padding(horizontal = 10.dp)) {
            val dash = InkPath.pattern(style)
            val stroke = 3.dp.toPx()
            drawLine(
                colors.onSurface,
                Offset(0f, size.height / 2),
                Offset(size.width, size.height / 2),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
                pathEffect = dash?.let { PathEffect.dashPathEffect(FloatArray(it.size) { i -> it[i] * stroke }) },
            )
        }
    }
}

/** "Aa" in the font it picks. */
@Composable
private fun FontChip(font: TextFont, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val family = when (font) {
        TextFont.Sans -> FontFamily.SansSerif
        TextFont.Serif -> FontFamily.Serif
        TextFont.Mono -> FontFamily.Monospace
        TextFont.Hand -> FontFamily.Cursive
    }
    val name = stringResource(
        when (font) {
            TextFont.Sans -> R.string.document_font_sans
            TextFont.Serif -> R.string.document_font_serif
            TextFont.Mono -> R.string.document_font_mono
            TextFont.Hand -> R.string.document_font_hand
        },
    )
    Surface(
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.size(width = 52.dp, height = 40.dp).semantics { contentDescription = name },
    ) {
        Box(contentAlignment = Alignment.Center) { Text("Aa", fontFamily = family, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun BoldChip(bold: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(R.string.document_text_bold)
    Surface(
        selected = bold,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (bold) colors.primaryContainer else colors.surfaceContainer,
        contentColor = if (bold) colors.onPrimaryContainer else colors.onSurface,
        modifier = Modifier.size(40.dp).semantics { contentDescription = name },
    ) {
        Box(contentAlignment = Alignment.Center) { Text("B", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
    }
}

/** What's behind text: nothing (a crossed square), or a fill. */
@Composable
private fun BackgroundChip(fill: Int?, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(if (fill == null) R.string.document_text_no_background else R.string.document_text_background)
    Box(
        Modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.onSurface else colors.outline.copy(alpha = 0.5f), MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(6.dp)
            .background(fill?.let { Color(it) } ?: Color.Transparent, MaterialTheme.shapes.extraSmall),
        contentAlignment = Alignment.Center,
    ) {
        if (fill == null) Icon(Icons.Rounded.FormatColorReset, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** A spoken name for a swatch. */
@Composable
private fun colorName(argb: Int): String = stringResource(
    when (argb) {
        PEN_COLORS[0] -> R.string.document_color_black
        PEN_COLORS[1] -> R.string.document_color_blue
        PEN_COLORS[2] -> R.string.document_color_red
        PEN_COLORS[3] -> R.string.document_color_green
        PEN_COLORS[4] -> R.string.document_color_purple
        PEN_COLORS[5] -> R.string.document_color_orange
        PEN_COLORS[6] -> R.string.document_color_brown
        PEN_COLORS[7] -> R.string.document_color_white
        HIGHLIGHTER_COLORS[0] -> R.string.document_color_yellow
        HIGHLIGHTER_COLORS[1] -> R.string.document_color_green
        HIGHLIGHTER_COLORS[2] -> R.string.document_color_pink
        HIGHLIGHTER_COLORS[3] -> R.string.document_color_blue
        else -> R.string.document_color_orange
    },
)

/** The page with its marks; a finger draws, erases, or drags the signature being placed. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MarkupCanvas(
    base: ImageBitmap,
    state: MarkupUiState,
    onStroke: (List<Corner>) -> Unit,
    onErase: (Corner, Float) -> Unit,
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
                    detectDragsFromTouch(
                        onDragStart = { start ->
                            current.clear()
                            if (!placing && tool == MarkupTool.Text) {
                                movingText = onTextDragStart(toPage(start))
                            } else if (!placing) {
                                if (tool == MarkupTool.Eraser) onErase(toPage(start), pageAspect) else current.add(toPage(start))
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            when {
                                placing -> onMoveSignature(drag.x / shown.width, drag.y / shown.height)
                                tool == MarkupTool.Text -> if (movingText >= 0) onTextDrag(movingText, drag.x / shown.width, drag.y / shown.height)
                                tool == MarkupTool.Eraser -> onErase(toPage(change.position), pageAspect)
                                // Every point the finger passed since the last frame, not just the
                                // latest: fast strokes stay curves instead of turning into corners.
                                else -> {
                                    change.historical.forEach { current.add(toPage(it.position)) }
                                    current.add(toPage(change.position))
                                }
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
                        if (tool == MarkupTool.Eraser) onErase(toPage(at), pageAspect) else onStroke(listOf(toPage(at)))
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
                // Dashed frames say "tap to change, drag to move"; the selected one is solid.
                state.texts.forEachIndexed { index, t ->
                    val (hw, hh) = PageRenderer.textHalfSize(t, shown.width).let { if (t.angle % 180 != 0) it.second to it.first else it }
                    val pad = 4.dp.toPx()
                    drawRect(
                        color = Color(0xFF7D7369),
                        topLeft = Offset(shown.left + t.x * shown.width - hw - pad, shown.top + t.y * shown.height - hh - pad),
                        size = Size((hw + pad) * 2, (hh + pad) * 2),
                        style = if (index == state.selectedText) {
                            Stroke(2.dp.toPx())
                        } else {
                            Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f)))
                        },
                    )
                }
            }
            if (current.isNotEmpty()) drawInk(MarkupViewModel.strokeFor(state, current.toList()), shown)
            state.placement?.let { placement ->
                val strokes = MarkupViewModel.signatureStrokes(placement, pageAspect)
                strokes.forEach { drawInk(InkMark(it, state.pen.color, 0.0035f), shown) }
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
    // The same smoothing and dashes the saved page gets.
    val path = Path().apply {
        InkPath.smooth(mark.points).forEachIndexed { i, p ->
            val x = shown.left + p.x * shown.width
            val y = shown.top + p.y * shown.height
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
    val dash = InkPath.pattern(mark.style)?.let { d -> PathEffect.dashPathEffect(FloatArray(d.size) { d[it] * width }) }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash), blendMode = blend)
}

/**
 * Type text, or pick today's date or a stamp word. [initial] is the text being changed, or
 * null for new text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextDialog(
    initial: TextMark?,
    retype: Boolean = false,
    onDone: (text: String, stamp: Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
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
        title = {
            Text(
                stringResource(
                    when {
                        retype -> R.string.document_text_retype
                        initial == null -> R.string.document_text_add
                        else -> R.string.document_text_change
                    },
                ),
            )
        },
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
                // Stamps and dates are for new text; a retyped word is just a word.
                if (!retype) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                if (!retype) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = stamp, onCheckedChange = { stamp = it }, colors = CheckboxDefaults.colors(checkedColor = colors.primary, checkmarkColor = colors.onPrimary))
                    Text(stringResource(R.string.document_text_stamp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text, stamp) }, enabled = text.isNotBlank()) {
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
                                            drawInk(InkMark(s, PEN_COLORS.first(), 0.025f), box)
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
                            detectDragsFromTouch(
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
                            drawPath(path, Color(PEN_COLORS.first()), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
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
