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
import dev.olaf.oscanner.core.model.Page
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

enum class MarkupTool { Pen, Highlighter, Eraser }

/** A signature being positioned before it becomes strokes on the page. */
data class Placement(val signature: Signature, val centerX: Float, val centerY: Float, val width: Float)

data class MarkupUiState(
    val page: Page? = null,
    /** The page without its marks, so they can be drawn and changed live on top. */
    val base: Bitmap? = null,
    val marks: List<InkMark> = emptyList(),
    val tool: MarkupTool = MarkupTool.Pen,
    val color: Int = INK_COLORS.first(),
    val placement: Placement? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val changed: Boolean get() = page != null && marks != page.edits.marks
}

/** Black, blue and red: what people sign and annotate paper with. */
val INK_COLORS = listOf(0xFF1A1A1A.toInt(), 0xFF1F4FB0.toInt(), 0xFFC62828.toInt())
/** Light, so multiplying it over paper reads as a highlighter and leaves text black. */
const val HIGHLIGHTER_COLOR = 0xFFFFEE58.toInt()
private const val PEN_WIDTH = 0.004f
private const val HIGHLIGHTER_WIDTH = 0.03f
private const val SIGNATURE_PEN_WIDTH = 0.0035f

class MarkupViewModel(
    private val store: PageStore,
    val signatures: SignatureStore,
    private val documentId: Long,
    private val pageIndex: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(MarkupUiState())
    val state: StateFlow<MarkupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            signatures.load()
            val page = store.pages(documentId).getOrNull(pageIndex) ?: return@launch
            val base = withContext(Dispatchers.Default) {
                val source = PageRenderer.loadUpright(page.originalPath, 2400)
                PageRenderer.render(source, page.edits.copy(marks = emptyList()), 1800).also { source.recycle() }
            }
            _state.update { it.copy(page = page, base = base, marks = page.edits.marks) }
        }
    }

    fun setTool(tool: MarkupTool) = _state.update { it.copy(tool = tool) }

    fun setColor(color: Int) = _state.update { it.copy(color = color, tool = MarkupTool.Pen) }

    /** A finished stroke from the canvas, in page coordinates. */
    fun addStroke(points: List<Corner>) {
        if (points.isEmpty()) return
        _state.update {
            val highlighter = it.tool == MarkupTool.Highlighter
            it.copy(
                marks = it.marks + InkMark(
                    points = points,
                    color = if (highlighter) HIGHLIGHTER_COLOR else it.color,
                    width = if (highlighter) HIGHLIGHTER_WIDTH else PEN_WIDTH,
                    highlighter = highlighter,
                ),
            )
        }
    }

    /** Removes every stroke passing within [radius] (page widths) of [point]. */
    fun eraseAt(point: Corner, radius: Float, pageAspect: Float) {
        _state.update { state ->
            state.copy(
                marks = state.marks.filterNot { mark ->
                    mark.points.any { hypot(it.x - point.x, (it.y - point.y) / pageAspect) <= radius + mark.width / 2 }
                },
            )
        }
    }

    fun undo() = _state.update { it.copy(marks = it.marks.dropLast(1)) }

    /** Starts positioning [signature]: lower right, a third of the page wide, where people sign. */
    fun place(signature: Signature) = _state.update { it.copy(placement = Placement(signature, 0.68f, 0.85f, 0.34f)) }

    fun moveSignature(dx: Float, dy: Float) = _state.update { s ->
        s.copy(placement = s.placement?.let { it.copy(centerX = (it.centerX + dx).coerceIn(0f, 1f), centerY = (it.centerY + dy).coerceIn(0f, 1f)) })
    }

    fun resizeSignature(width: Float) = _state.update { s -> s.copy(placement = s.placement?.copy(width = width.coerceIn(0.1f, 0.9f))) }

    fun cancelPlacement() = _state.update { it.copy(placement = null) }

    /** Turns the positioned signature into pen strokes on the page. */
    fun confirmPlacement(pageAspect: Float) = _state.update { s ->
        val p = s.placement ?: return@update s
        val strokes = signatureStrokes(p, pageAspect).map { InkMark(it, s.color, SIGNATURE_PEN_WIDTH) }
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
            store.setEdits(page.id, page.edits.copy(marks = s.marks))
            _state.update { it.copy(saving = false, saved = true) }
        }
    }

    companion object {
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
