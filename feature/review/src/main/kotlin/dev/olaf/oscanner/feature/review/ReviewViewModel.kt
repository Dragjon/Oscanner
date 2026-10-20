package dev.olaf.oscanner.feature.review

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Crop mode: the full photo with a draggable outline. */
data class CropDraft(val pageId: Long, val quad: Quad) {
    val valid: Boolean get() = QuadGeometry.isConvex(quad) && QuadGeometry.area(quad.corners) > 0.01
}

class ReviewViewModel(
    private val store: PageStore,
    val documentId: Long,
) : ViewModel() {

    val pages: StateFlow<List<Page>?> = store.observePages(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _crop = MutableStateFlow<CropDraft?>(null)
    val crop: StateFlow<CropDraft?> = _crop.asStateFlow()

    /** Decoding and OpenCV work on big bitmaps, one at a time. */
    private val work = Mutex()
    private val sources = LruCache<String, Bitmap>(3)
    private val previews = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The page as it will look, at screen size. Cached per page + recipe. */
    suspend fun preview(page: Page): Bitmap = cachedOrMake("p:${page.id}:${page.edits}") {
        PageRenderer.render(source(page.originalPath), page.edits, PREVIEW_SIDE)
    }

    /** Small previews of every look, made from this very page. */
    suspend fun filterPreviews(page: Page): Map<PageFilter, Bitmap> {
        val straightKey = "s:${page.id}:${page.edits.quad}:${page.edits.rotation}:${page.edits.flatten}:${page.edits.curve}:${page.edits.descreen}"
        val straight = cachedOrMake(straightKey) {
            PageRenderer.straighten(source(page.originalPath), page.edits, FILTER_THUMB_SIDE)
        }
        return PageFilter.entries.associateWith { filter ->
            cachedOrMake("$straightKey:$filter") { PageRenderer.applyFilter(straight, filter) }
        }
    }

    /** The upright original for crop mode. */
    suspend fun original(page: Page): Bitmap = work.withLock { withContext(Dispatchers.Default) { source(page.originalPath) } }

    fun setFilter(page: Page, filter: PageFilter) = update(page) { it.copy(filter = filter) }

    fun applyFilterToAll(filter: PageFilter) {
        viewModelScope.launch {
            pages.value.orEmpty().filter { it.edits.filter != filter }.forEach { store.setEdits(it.id, it.edits.copy(filter = filter)) }
        }
    }

    fun rotate(page: Page) = update(page) { it.rotatedClockwise() }

    fun setAdjustments(page: Page, brightness: Float, contrast: Float, detail: Float) =
        update(page) { it.copy(brightness = brightness, contrast = contrast, detail = detail) }

    fun setFlatten(page: Page, on: Boolean) = update(page) { it.copy(flatten = on) }

    fun setDescreen(page: Page, on: Boolean) = update(page) { it.copy(descreen = on) }

    fun startCrop(page: Page) {
        _crop.value = CropDraft(page.id, page.edits.quad)
    }

    fun moveCorner(index: Int, x: Float, y: Float) {
        _crop.update { draft ->
            draft?.copy(
                quad = Quad.of(
                    draft.quad.corners.mapIndexed { i, c ->
                        if (i == index) Corner(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f)) else c
                    },
                ),
            )
        }
    }

    fun cropWholePhoto() = _crop.update { it?.copy(quad = Quad.Full) }

    fun cancelCrop() {
        _crop.value = null
    }

    fun applyCrop() {
        val draft = _crop.value?.takeIf { it.valid } ?: return
        val page = pages.value?.firstOrNull { it.id == draft.pageId } ?: return
        _crop.value = null
        update(page) { it.copy(quad = draft.quad) }
    }

    fun finishEditing() {
        viewModelScope.launch { store.finishedEditing(documentId) }
    }

    fun deletePage(page: Page) {
        viewModelScope.launch { store.deletePage(page.id) }
    }

    private fun update(page: Page, change: (PageEdits) -> PageEdits) {
        viewModelScope.launch { store.setEdits(page.id, change(page.edits)) }
    }

    private suspend fun cachedOrMake(key: String, make: () -> Bitmap): Bitmap {
        previews.get(key)?.let { return it }
        return work.withLock {
            previews.get(key) ?: withContext(Dispatchers.Default) { make() }.also { previews.put(key, it) }
        }
    }

    /** Callers hold [work]. */
    private fun source(path: String): Bitmap =
        sources.get(path) ?: PageRenderer.loadUpright(path, SOURCE_SIDE).also { sources.put(path, it) }

    override fun onCleared() {
        previews.evictAll()
        sources.evictAll()
    }

    private companion object {
        const val SOURCE_SIDE = 2000
        const val PREVIEW_SIDE = 1600
        const val FILTER_THUMB_SIDE = 520
    }
}
