package dev.olaf.oscanner.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.FolderRepository
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.data.TagRepository
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.SortOrder
import dev.olaf.oscanner.core.model.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class LibraryUiState(
    val loading: Boolean = true,
    /** False until the first document exists; drives the empty state. */
    val hasDocuments: Boolean = false,
    val documents: List<Document> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val selectedFolderId: Long? = null,
    val tags: List<Tag> = emptyList(),
    val selectedTagId: Long? = null,
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.Newest,
)

/** Something done to several documents that the screen offers to undo. */
sealed interface LibraryEvent {
    data class Trashed(val ids: List<Long>) : LibraryEvent
    data class Merged(val mergedId: Long, val sources: List<Long>) : LibraryEvent
}

private data class Filters(val folder: Long?, val tag: Long?, val query: String, val sort: SortOrder)

class LibraryViewModel(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val tags: TagRepository,
    private val settings: SettingsRepository,
    private val pages: PageStore,
) : ViewModel() {

    /** Chosen documents while selecting, in the order they were picked; null when not selecting. */
    private val _selection = MutableStateFlow<List<Long>?>(null)
    val selection: StateFlow<List<Long>?> = _selection.asStateFlow()

    private val events = Channel<LibraryEvent>(Channel.BUFFERED)
    val eventFlow: Flow<LibraryEvent> = events.receiveAsFlow()

    fun startSelecting(firstId: Long? = null) {
        _selection.value = listOfNotNull(firstId)
    }

    fun toggleSelected(id: Long) {
        _selection.update { current -> current?.let { if (id in it) it - id else it + id } }
    }

    fun stopSelecting() {
        _selection.value = null
    }

    fun moveSelected(folderId: Long?) {
        val ids = _selection.value.orEmpty()
        _selection.value = null
        viewModelScope.launch { ids.forEach { documents.move(it, folderId) } }
    }

    fun trashSelected() {
        val ids = _selection.value.orEmpty()
        if (ids.isEmpty()) return
        _selection.value = null
        viewModelScope.launch {
            ids.forEach { documents.moveToTrash(it) }
            events.send(LibraryEvent.Trashed(ids))
        }
    }

    private val _preparing = MutableStateFlow(false)
    /** True while the chosen documents are being made into PDFs to share. */
    val preparing: StateFlow<Boolean> = _preparing.asStateFlow()

    private val shares = Channel<List<File>>(Channel.BUFFERED)
    /** Finished PDFs, ready for the share sheet. */
    val shareFlow: Flow<List<File>> = shares.receiveAsFlow()

    fun shareSelected() {
        val ids = _selection.value.orEmpty()
        if (ids.isEmpty() || _preparing.value) return
        _selection.value = null
        _preparing.value = true
        viewModelScope.launch {
            runCatching { pages.exportPdfs(ids) }.onSuccess { shares.send(it) }
            _preparing.value = false
        }
    }

    fun restore(ids: List<Long>) {
        viewModelScope.launch { ids.forEach { documents.restore(it) } }
    }

    /** Merges in the order the documents were picked; the first one's name is kept. */
    fun mergeSelected() {
        val ids = _selection.value.orEmpty()
        if (ids.size < 2) return
        _selection.value = null
        viewModelScope.launch {
            val title = state.value.documents.firstOrNull { it.id == ids.first() }?.title ?: return@launch
            runCatching { pages.merge(ids, title) }.onSuccess { events.send(LibraryEvent.Merged(it, ids)) }
        }
    }

    fun unmerge(event: LibraryEvent.Merged) {
        viewModelScope.launch { pages.unmerge(event.mergedId, event.sources) }
    }

    private val query = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<Long?>(null)
    private val selectedTag = MutableStateFlow<Long?>(null)
    private val sortOrder = settings.settings.map { it.sortOrder }

    private val filters = combine(selectedFolder, selectedTag, query, sortOrder, ::Filters)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val visibleDocuments = filters
        .flatMapLatest { f -> documents.observeDocuments(f.folder, f.tag, f.query, f.sort) }

    val state: StateFlow<LibraryUiState> = combine(
        documents.observeHasDocuments(),
        visibleDocuments,
        folders.observeFolders(),
        tags.observeTags(),
        filters,
    ) { hasDocuments, docs, folderList, tagList, f ->
        LibraryUiState(
            loading = false,
            hasDocuments = hasDocuments,
            documents = docs,
            folders = folderList,
            selectedFolderId = f.folder.takeIf { id -> folderList.any { it.id == id } },
            tags = tagList,
            selectedTagId = f.tag.takeIf { id -> tagList.any { it.id == id } },
            query = f.query,
            sortOrder = f.sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onFolderSelected(id: Long?) {
        selectedFolder.value = id
    }

    /** Tapping the chosen tag again clears it. */
    fun onTagSelected(id: Long) {
        selectedTag.update { if (it == id) null else id }
    }

    fun onSortOrderChange(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { selectedFolder.value = folders.createFolder(name) }
    }

    fun renameFolder(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { folders.renameFolder(id, name) }
    }

    suspend fun documentCount(folderId: Long): Int = folders.documentCount(folderId)

    /** Back to All once the folder is gone; documents put in Trash can be brought back with Undo. */
    fun deleteFolder(id: Long, trashDocuments: Boolean) {
        if (selectedFolder.value == id) selectedFolder.value = null
        viewModelScope.launch {
            val trashed = folders.deleteFolder(id, trashDocuments)
            if (trashed.isNotEmpty()) events.send(LibraryEvent.Trashed(trashed))
        }
    }
}
