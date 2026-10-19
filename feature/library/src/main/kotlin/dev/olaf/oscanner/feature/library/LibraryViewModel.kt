package dev.olaf.oscanner.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.FolderRepository
import dev.olaf.oscanner.core.data.SettingsRepository
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    /** False until the first document exists; drives the empty state. */
    val hasDocuments: Boolean = false,
    val documents: List<Document> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val selectedFolderId: Long? = null,
    val query: String = "",
    val sortOrder: SortOrder = SortOrder.Newest,
)

class LibraryViewModel(
    documents: DocumentRepository,
    private val folders: FolderRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val selectedFolder = MutableStateFlow<Long?>(null)
    private val sortOrder = settings.settings.map { it.sortOrder }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val visibleDocuments = combine(selectedFolder, query, sortOrder, ::Triple)
        .flatMapLatest { (folder, text, sort) -> documents.observeDocuments(folder, text, sort) }

    val state: StateFlow<LibraryUiState> = combine(
        documents.observeHasDocuments(),
        visibleDocuments,
        folders.observeFolders(),
        combine(selectedFolder, query, sortOrder, ::Triple),
    ) { hasDocuments, docs, folderList, (folder, text, sort) ->
        LibraryUiState(
            loading = false,
            hasDocuments = hasDocuments,
            documents = docs,
            folders = folderList,
            selectedFolderId = folder.takeIf { id -> folderList.any { it.id == id } },
            query = text,
            sortOrder = sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun onFolderSelected(id: Long?) {
        selectedFolder.value = id
    }

    fun onSortOrderChange(order: SortOrder) {
        viewModelScope.launch { settings.setSortOrder(order) }
    }

    fun createFolder(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { selectedFolder.value = folders.createFolder(name) }
    }
}
