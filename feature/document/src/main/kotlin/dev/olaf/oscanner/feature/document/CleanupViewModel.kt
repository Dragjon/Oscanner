package dev.olaf.oscanner.feature.document

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageEdits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CleanupBrush { Erase, Keep }

/** Brush widths as a share of page width. */
enum class BrushSize(val width: Float) { Small(0.015f), Medium(0.035f), Large(0.07f) }

data class CleanupUiState(
    val page: Page? = null,
    /** The page before any clean-up: what the hatch is drawn over. */
    val before: Bitmap? = null,
    /** The page as it will look; shown while Compare is held. */
    val after: Bitmap? = null,
    /** Opaque where something will be removed. */
    val mask: Bitmap? = null,
    val edits: PageEdits? = null,
    val brush: CleanupBrush = CleanupBrush.Erase,
    val size: BrushSize = BrushSize.Medium,
    val working: Boolean = false,
    /** Reading the page to tell its print from handwriting. */
    val findingPrint: Boolean = false,
    val saved: Boolean = false,
    /** Every page of the document, to pick others the automatic clean-up should go to. */
    val pages: List<Page> = emptyList(),
    /** How many other pages were just given the automatic clean-up. */
    val appliedTo: Int = 0,
) {
    /** Handwriting removal is on but no printed words were found: all writing goes. */
    val noPrintFound: Boolean get() = edits?.removePenInk == true && edits.printed?.isEmpty() == true
    val changed: Boolean get() = page != null && edits != null && edits != page.edits
}

class CleanupViewModel(
    private val store: PageStore,
    private val documentId: Long,
    private val pageIndex: Int,
) : ViewModel() {

    private val _state = MutableStateFlow(CleanupUiState())
    val state: StateFlow<CleanupUiState> = _state.asStateFlow()

    private var source: Bitmap? = null
    private val work = Mutex()
    private var preview: Job? = null
    /** Which list each stroke of this session went into, newest last, for Undo. */
    private val strokeOrder = mutableListOf<CleanupBrush>()

    init {
        viewModelScope.launch {
            val pages = store.pages(documentId)
            val page = pages.getOrNull(pageIndex) ?: return@launch
            _state.update { it.copy(pages = pages) }
            val bitmap = withContext(Dispatchers.Default) { PageRenderer.loadUpright(page.originalPath, SOURCE_SIDE) }
            source = bitmap
            val before = withContext(Dispatchers.Default) {
                PageRenderer.render(bitmap, page.edits.withoutCleanup(), PREVIEW_SIDE)
            }
            _state.update { it.copy(page = page, before = before, edits = page.edits) }
            refresh(immediately = true)
        }
    }

    /**
     * Turning handwriting removal on first finds the printed words (once per page; kept in
     * the edits), since dark ink outside them is what counts as handwriting.
     */
    fun setRemovePenInk(on: Boolean) {
        val s = _state.value
        val page = s.page ?: return
        val edits = s.edits ?: return
        if (!on || edits.printed != null) {
            edit { it.copy(removePenInk = on) }
            return
        }
        if (s.findingPrint) return
        _state.update { it.copy(findingPrint = true) }
        viewModelScope.launch {
            val printed = runCatching { store.printedAreas(page, edits) }.getOrNull()
            _state.update { it.copy(findingPrint = false) }
            // Unreadable (no language data, say): colored pen is still found.
            edit { it.copy(removePenInk = true, printed = printed) }
        }
    }

    fun setRemoveFingers(on: Boolean) = edit { it.copy(removeFingers = on) }

    /** Gives [pageIds] this page's automatic clean-up: each page finds its own handwriting and fingers. */
    fun applyAutoTo(pageIds: Set<Long>) {
        val edits = _state.value.edits ?: return
        if (pageIds.isEmpty()) return
        store.autoCleanup(pageIds.toList(), handwriting = edits.removePenInk, fingers = edits.removeFingers)
        _state.update { it.copy(appliedTo = pageIds.size) }
    }

    fun setBrush(brush: CleanupBrush) = _state.update { it.copy(brush = brush) }

    fun setSize(size: BrushSize) = _state.update { it.copy(size = size) }

    fun addStroke(points: List<Corner>) {
        if (points.isEmpty()) return
        val s = _state.value
        val stroke = InkMark(points, 0, s.size.width)
        strokeOrder += s.brush
        edit { if (s.brush == CleanupBrush.Erase) it.copy(erase = it.erase + stroke) else it.copy(keep = it.keep + stroke) }
    }

    val canUndo: Boolean get() = strokeOrder.isNotEmpty()

    /** Takes back the last brush stroke painted here, Erase or Keep. */
    fun undoStroke() {
        val last = strokeOrder.removeLastOrNull() ?: return
        edit { if (last == CleanupBrush.Erase) it.copy(erase = it.erase.dropLast(1)) else it.copy(keep = it.keep.dropLast(1)) }
    }

    fun save() {
        val s = _state.value
        val page = s.page ?: return
        val edits = s.edits ?: return
        viewModelScope.launch {
            store.setEdits(page.id, edits)
            _state.update { it.copy(saved = true) }
        }
    }

    private fun edit(change: (PageEdits) -> PageEdits) {
        _state.update { s -> s.edits?.let { s.copy(edits = change(it)) } ?: s }
        refresh(immediately = false)
    }

    /** Recomputes the mask and result, waiting briefly so quick taps don't each re-render. */
    private fun refresh(immediately: Boolean) {
        preview?.cancel()
        preview = viewModelScope.launch {
            if (!immediately) delay(250)
            val bitmap = source ?: return@launch
            val edits = _state.value.edits ?: return@launch
            _state.update { it.copy(working = true) }
            val (mask, after) = work.withLock {
                withContext(Dispatchers.Default) {
                    PageRenderer.cleanupMask(bitmap, edits, PREVIEW_SIDE) to PageRenderer.render(bitmap, edits.copy(marks = emptyList(), texts = emptyList()), PREVIEW_SIDE)
                }
            }
            _state.update { if (it.edits == edits) it.copy(mask = mask, after = after, working = false) else it }
        }
    }

    private companion object {
        const val SOURCE_SIDE = 2400
        const val PREVIEW_SIDE = 1400
    }
}

/** The same page with no clean-up and no drawn marks: the "before" for comparing. */
internal fun PageEdits.withoutCleanup() =
    copy(erase = emptyList(), keep = emptyList(), removePenInk = false, printed = null, removeFingers = false, marks = emptyList(), texts = emptyList())
