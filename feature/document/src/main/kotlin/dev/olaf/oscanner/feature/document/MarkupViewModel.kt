package dev.olaf.oscanner.feature.document

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.data.Signature
import dev.olaf.oscanner.core.data.SignatureStore
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.LineStyle
import dev.olaf.oscanner.core.model.TextFont
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.TextMark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

enum class MarkupTool { Pen, Highlighter, Text, Eraser }

/** A signature being positioned before it becomes strokes on the page. */
data class Placement(val signature: Signature, val centerX: Float, val centerY: Float, val width: Float)

/** The pen: its color, how thick (a share of page width), and solid, dashed or dotted. */
data class PenSettings(val color: Int = PEN_COLORS.first(), val width: Float = PEN_WIDTHS[1], val style: LineStyle = LineStyle.Solid)

data class HighlighterSettings(val color: Int = HIGHLIGHTER_COLORS.first(), val width: Float = HIGHLIGHTER_WIDTHS[1])

/** How new text looks, and the selected text while one is. */
data class TextSettings(
    val color: Int = PEN_COLORS.first(),
    val font: TextFont = TextFont.Sans,
    val bold: Boolean = false,
    val size: Float = TEXT_SIZES[1],
    val background: Int? = null,
)

data class MarkupUiState(
    val page: Page? = null,
    /** The page without its marks, so they can be drawn and changed live on top. */
    val base: Bitmap? = null,
    val marks: List<InkMark> = emptyList(),
    val texts: List<TextMark> = emptyList(),
    val tool: MarkupTool = MarkupTool.Pen,
    val pen: PenSettings = PenSettings(),
    val highlighter: HighlighterSettings = HighlighterSettings(),
    val text: TextSettings = TextSettings(),
    val eraserSize: Float = ERASER_SIZES[1],
    /** The text whose style the options change, in the Text tool; -1 for none. */
    val selectedText: Int = -1,
    val placement: Placement? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val changed: Boolean get() = page != null && (marks != page.edits.marks || texts != page.edits.texts)
}

/** Pen and text colors: black and blue first, as on paper, then the colors people mark up in. */
val PEN_COLORS = listOf(
    0xFF1A1A1A.toInt(), 0xFF1F4FB0.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt(),
    0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF5D4037.toInt(), 0xFFFFFFFF.toInt(),
)
/** Light, so multiplying them over paper reads as highlighter and leaves the text black. */
val HIGHLIGHTER_COLORS = listOf(0xFFFFEE58.toInt(), 0xFFA5E887.toInt(), 0xFFFFA7C4.toInt(), 0xFF9ED8F7.toInt(), 0xFFFFC27A.toInt())
/** Fine, medium and bold, as a share of page width. */
val PEN_WIDTHS = listOf(0.0025f, 0.004f, 0.0075f)
val HIGHLIGHTER_WIDTHS = listOf(0.018f, 0.03f, 0.045f)
val ERASER_SIZES = listOf(0.012f, 0.02f, 0.04f)
/** Text letter heights as a share of page width: about 10, 14 and 22 pt on an A4 page. */
val TEXT_SIZES = listOf(0.017f, 0.024f, 0.038f)
/** Behind text: none, white, a highlighter yellow, or dark. */
val TEXT_BACKGROUNDS: List<Int?> = listOf(null, 0xFFFFFFFF.toInt(), 0xFFFFF59D.toInt(), 0xFF263238.toInt())
private const val SIGNATURE_PEN_WIDTH = 0.0035f

class MarkupViewModel(
    private val store: PageStore,
    val signatures: SignatureStore,
    private val documentId: Long,
    private val pageIndex: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(MarkupUiState())
    val state: StateFlow<MarkupUiState> = _state.asStateFlow()

    /** What the page looked like before each change made here, for Undo. */
    private val history = mutableListOf<Pair<List<InkMark>, List<TextMark>>>()

    val canUndo: Boolean get() = history.isNotEmpty()

    /** Records the current marks for Undo, then applies [change]. */
    private fun change(change: (MarkupUiState) -> MarkupUiState) = _state.update { s ->
        val next = change(s)
        if (next.marks != s.marks || next.texts != s.texts) history += s.marks to s.texts
        next
    }

    init {
        viewModelScope.launch {
            signatures.load()
            val page = store.pages(documentId).getOrNull(pageIndex) ?: return@launch
            val base = withContext(Dispatchers.Default) {
                val source = PageRenderer.loadUpright(page.originalPath, 2400)
                PageRenderer.render(source, page.edits.copy(marks = emptyList(), texts = emptyList()), 1800).also { source.recycle() }
            }
            _state.update { it.copy(page = page, base = base, marks = page.edits.marks, texts = page.edits.texts) }
        }
    }

    fun setTool(tool: MarkupTool) = _state.update { it.copy(tool = tool, selectedText = -1) }

    fun setPen(pen: PenSettings) = _state.update { it.copy(pen = pen) }

    fun setHighlighter(highlighter: HighlighterSettings) = _state.update { it.copy(highlighter = highlighter) }

    fun setEraserSize(size: Float) = _state.update { it.copy(eraserSize = size) }

    /** New text's style; with a text selected, that text takes it too (one Undo step per change). */
    fun setTextStyle(style: TextSettings) {
        val selected = _state.value.selectedText
        if (selected in _state.value.texts.indices) {
            change { s ->
                s.copy(
                    text = style,
                    texts = s.texts.mapIndexed { i, t ->
                        if (i == selected) t.copy(color = style.color, font = style.font, bold = style.bold, size = style.size, background = style.background) else t
                    },
                )
            }
        } else {
            _state.update { it.copy(text = style) }
        }
    }

    /** Selects text [index] so the options show and change its style. */
    fun selectText(index: Int) = _state.update { s ->
        val t = s.texts.getOrNull(index) ?: return@update s.copy(selectedText = -1)
        s.copy(selectedText = index, text = TextSettings(t.color, t.font, t.bold, t.size, t.background))
    }

    /** A finished stroke from the canvas, in page coordinates. */
    fun addStroke(points: List<Corner>) {
        if (points.isEmpty()) return
        change {
            it.copy(marks = it.marks + strokeFor(it, points))
        }
    }

    /** Removes every stroke passing within [radius] (page widths) of [point]. */
    fun eraseAt(point: Corner, radius: Float, pageAspect: Float) {
        change { state ->
            state.copy(
                marks = state.marks.filterNot { mark ->
                    mark.points.any { hypot(it.x - point.x, (it.y - point.y) / pageAspect) <= radius + mark.width / 2 }
                },
            )
        }
    }

    fun undo() {
        val (marks, texts) = history.removeLastOrNull() ?: return
        _state.update { it.copy(marks = marks, texts = texts) }
    }

    /** Puts new text centered on [at], in the text options' style, and selects it. */
    fun addText(text: String, at: Corner, stamp: Boolean) {
        if (text.isBlank()) return
        change {
            val style = it.text
            val mark = TextMark(text.trim(), at.x, at.y, style.size, style.color, stamp = stamp, font = style.font, bold = style.bold, background = style.background)
            it.copy(texts = it.texts + mark, selectedText = it.texts.size)
        }
    }

    fun updateText(index: Int, text: String, stamp: Boolean) {
        if (text.isBlank()) return removeText(index)
        change { s -> s.copy(texts = s.texts.mapIndexed { i, t -> if (i == index) t.copy(text = text.trim(), stamp = stamp) else t }) }
    }

    fun removeText(index: Int) = change { s -> s.copy(texts = s.texts.filterIndexed { i, _ -> i != index }, selectedText = -1) }

    /** Starts a drag of text [index]; Undo takes back the whole move, not each step. */
    fun startMovingText() {
        val s = _state.value
        history += s.marks to s.texts
    }

    fun moveText(index: Int, dx: Float, dy: Float) = _state.update { s ->
        s.copy(texts = s.texts.mapIndexed { i, t -> if (i == index) t.copy(x = (t.x + dx).coerceIn(0f, 1f), y = (t.y + dy).coerceIn(0f, 1f)) else t })
    }

    /**
     * The topmost text under [point] on a page [pageWidth]×[pageHeight] pixels, or -1. Its box
     * is measured with the same paint the page is drawn with.
     */
    fun textAt(point: Corner, pageWidth: Float, pageHeight: Float): Int = _state.value.texts.indexOfLast { t ->
        val (hw, hh) = PageRenderer.textHalfSize(t, pageWidth)
        // Turned a quarter, a text's box swaps width and height.
        val sideways = t.angle % 180 != 0
        val halfX = (if (sideways) hh else hw) / pageWidth
        val halfY = (if (sideways) hw else hh) / pageHeight
        kotlin.math.abs(point.x - t.x) <= halfX && kotlin.math.abs(point.y - t.y) <= halfY
    }

    /** Starts positioning [signature]: lower right, a third of the page wide, where people sign. */
    fun place(signature: Signature) = _state.update { it.copy(placement = Placement(signature, 0.68f, 0.85f, 0.34f)) }

    fun moveSignature(dx: Float, dy: Float) = _state.update { s ->
        s.copy(placement = s.placement?.let { it.copy(centerX = (it.centerX + dx).coerceIn(0f, 1f), centerY = (it.centerY + dy).coerceIn(0f, 1f)) })
    }

    fun resizeSignature(width: Float) = _state.update { s -> s.copy(placement = s.placement?.copy(width = width.coerceIn(0.1f, 0.9f))) }

    fun cancelPlacement() = _state.update { it.copy(placement = null) }

    /** Turns the positioned signature into pen strokes on the page. */
    fun confirmPlacement(pageAspect: Float) = change { s ->
        val p = s.placement ?: return@change s
        val strokes = signatureStrokes(p, pageAspect).map { InkMark(it, s.pen.color, SIGNATURE_PEN_WIDTH) }
        s.copy(marks = s.marks + strokes, placement = null)
    }

    fun saveSignature(strokes: List<List<Corner>>, aspect: Float, keep: Boolean) {
        viewModelScope.launch {
            val signature = if (keep) {
                signatures.save(strokes, aspect)
            } else {
                Signature("unsaved", strokes, aspect)
            }
            place(signature)
        }
    }

    fun deleteSignature(id: String) {
        viewModelScope.launch { signatures.delete(id) }
    }

    fun save() {
        val s = _state.value
        val page = s.page ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            store.setEdits(page.id, page.edits.copy(marks = s.marks, texts = s.texts))
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    companion object {
        /** The stroke the current tool lays down along [points]. */
        fun strokeFor(state: MarkupUiState, points: List<Corner>): InkMark = if (state.tool == MarkupTool.Highlighter) {
            InkMark(points, state.highlighter.color, state.highlighter.width, highlighter = true)
        } else {
            InkMark(points, state.pen.color, state.pen.width, style = state.pen.style)
        }

        /** Where a placed signature's strokes land on the page, in page coordinates. */
        fun signatureStrokes(p: Placement, pageAspect: Float): List<List<Corner>> {
            // Height as a share of page height: width share × page width ÷ signature aspect ÷ page height.
            val height = p.width * pageAspect / p.signature.aspect
            val left = p.centerX - p.width / 2
            val top = p.centerY - height / 2
            return p.signature.strokes.map { stroke -> stroke.map { Corner(left + it.x * p.width, top + it.y * height) } }
        }
    }
}
