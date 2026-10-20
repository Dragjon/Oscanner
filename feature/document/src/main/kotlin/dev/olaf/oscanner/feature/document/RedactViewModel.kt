package dev.olaf.oscanner.feature.document

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.Quad
import dev.olaf.oscanner.core.model.WordBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RedactUiState(
    val page: Page? = null,
    /** The page with nothing blacked out, so boxes can be added and taken away live. */
    val base: Bitmap? = null,
    /** Blacked-out areas, in photo coordinates like [dev.olaf.oscanner.core.model.PageEdits.redactions]. */
    val areas: List<Quad> = emptyList(),
    /** Words read on the page; empty if it hasn't been read yet. */
    val words: List<WordBox> = emptyList(),
    val saved: Boolean = false,
) {
    val changed: Boolean get() = page != null && areas != page.edits.redactions

    /** [areas] as outlines on the page as shown, for drawing and tapping. */
    val shapes: List<List<Corner>>
        get() = page?.let { p -> areas.map { a -> a.corners.map { QuadGeometry.sourceToPage(p.edits.quad, p.edits.rotation, it) } } }.orEmpty()

    /** Height ÷ width of the page as shown. */
    val aspect: Float get() = base?.let { it.height.toFloat() / it.width } ?: 1.414f

    /** Words not already under a box, so suggestions count only what's still visible. */
    val uncovered: List<WordBox>
        get() {
            val covered = shapes
            return words.filterNot { w ->
                val center = Corner((w.left + w.right) / 2, (w.top + w.bottom) / 2)
                covered.any { QuadGeometry.contains(it, center) }
            }
        }
}

class RedactViewModel(
    private val store: PageStore,
    private val documentId: Long,
    private val pageIndex: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(RedactUiState())
    val state: StateFlow<RedactUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val page = store.pages(documentId).getOrNull(pageIndex) ?: return@launch
            val base = withContext(Dispatchers.Default) {
                val source = PageRenderer.loadUpright(page.originalPath, SOURCE_SIDE)
                PageRenderer.render(source, page.edits.copy(redactions = emptyList()), PREVIEW_SIDE).also { source.recycle() }
            }
            val words = store.words(page.id)
            _state.update { it.copy(page = page, base = base, areas = page.edits.redactions, words = words) }
        }
    }

    /** A box dragged out on the page, corner to corner, in page coordinates. */
    fun addBox(a: Corner, b: Corner) {
        val rect = PageRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        // A slip of the finger, not a box.
        if (rect.right - rect.left < MIN_SIDE || rect.bottom - rect.top < MIN_SIDE) return
        addRects(listOf(rect))
    }

    /** Covers each group of words found by [PrivateDetails]. */
    fun cover(groups: List<List<WordBox>>) {
        val aspect = _state.value.aspect
        addRects(groups.flatMap { PrivateDetails.rects(it, aspect) })
    }

    /** Takes away the topmost box under [point]; true if there was one. */
    fun removeAt(point: Corner): Boolean {
        val s = _state.value
        val index = s.shapes.indexOfLast { QuadGeometry.contains(it, point) }
        if (index < 0) return false
        _state.update { it.copy(areas = it.areas.filterIndexed { i, _ -> i != index }) }
        return true
    }

    fun undo() = _state.update { it.copy(areas = it.areas.dropLast(1)) }

    fun save() {
        val s = _state.value
        val page = s.page ?: return
        viewModelScope.launch {
            store.setEdits(page.id, page.edits.copy(redactions = s.areas))
            _state.update { it.copy(saved = true) }
        }
    }

    private fun addRects(rects: List<PageRect>) {
        val page = _state.value.page ?: return
        val quad = page.edits.quad
        val rotation = page.edits.rotation
        fun toSource(x: Float, y: Float) = QuadGeometry.pageToSource(quad, rotation, Corner(x, y))
        val areas = rects.map { r ->
            Quad(toSource(r.left, r.top), toSource(r.right, r.top), toSource(r.right, r.bottom), toSource(r.left, r.bottom))
        }
        _state.update { it.copy(areas = it.areas + areas) }
    }

    private companion object {
        const val SOURCE_SIDE = 2400
        const val PREVIEW_SIDE = 1800
        const val MIN_SIDE = 0.01f
    }
}
