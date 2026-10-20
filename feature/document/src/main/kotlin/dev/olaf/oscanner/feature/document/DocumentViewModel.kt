package dev.olaf.oscanner.feature.document

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.FolderRepository
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.data.TagRepository
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Folder
import dev.olaf.oscanner.core.model.Page
import dev.olaf.oscanner.core.model.Tag
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class DocumentUiState(
    val loading: Boolean = true,
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Every tag in use, offered as suggestions. */
    val allTags: List<Tag> = emptyList(),
    /** Set while a PDF or images are being prepared; the dock shows it in words. */
    val busy: Busy? = null,
)

enum class Busy { PreparingPdf, PreparingImages, Saving, Importing }

/** Things the screen does with Android APIs once the files are ready. */
sealed interface DocumentEvent {
    data class SharePdf(val file: File) : DocumentEvent
    data class ShareImages(val files: List<File>) : DocumentEvent
    data class Print(val file: File, val title: String) : DocumentEvent
    data object Saved : DocumentEvent
    data object Failed : DocumentEvent
    data class Trashed(val documentId: Long) : DocumentEvent
}

class DocumentViewModel(
    private val documents: DocumentRepository,
    private val folders: FolderRepository,
    private val tags: TagRepository,
    private val store: PageStore,
    val documentId: Long,
) : ViewModel() {

    private val busy = MutableStateFlow<Busy?>(null)
    private val events = Channel<DocumentEvent>(Channel.BUFFERED)
    val eventFlow: Flow<DocumentEvent> = events.receiveAsFlow()

    val state: StateFlow<DocumentUiState> = combine(
        documents.observeDocument(documentId),
        store.observePages(documentId),
        combine(folders.observeFolders(), tags.observeTags(), ::Pair),
        busy,
    ) { document, pages, (folderList, tagList), working ->
        DocumentUiState(loading = false, document = document, pages = pages, folders = folderList, allTags = tagList, busy = working)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DocumentUiState())

    fun sharePdf(password: String? = null) = work(Busy.PreparingPdf) { DocumentEvent.SharePdf(store.exportPdf(documentId, password)) }

    fun shareImages() = work(Busy.PreparingImages) { DocumentEvent.ShareImages(store.exportJpegs(documentId)) }

    fun shareLongImage() = work(Busy.PreparingImages) { DocumentEvent.ShareImages(listOf(store.exportLongImage(documentId))) }

    fun print() = work(Busy.PreparingPdf) {
        DocumentEvent.Print(store.exportPdf(documentId), state.value.document?.title ?: "Scan")
    }

    /** Writes the PDF to a place the user picked with the system file picker. */
    fun savePdfTo(uri: Uri, open: (Uri) -> java.io.OutputStream?) = work(Busy.Saving) {
        val out = requireNotNull(open(uri)) { "Can't open $uri" }
        out.use { store.writePdf(documentId, it) }
        DocumentEvent.Saved
    }

    fun importPages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        work(Busy.Importing) {
            store.importImages(uris, title = "", documentId = documentId)
            null
        }
    }

    fun rename(title: String) {
        viewModelScope.launch { documents.rename(documentId, title) }
    }

    fun moveTo(folderId: Long?) {
        viewModelScope.launch { documents.move(documentId, folderId) }
    }

    fun addTag(name: String) {
        viewModelScope.launch { tags.addTag(documentId, name) }
    }

    fun removeTag(name: String) {
        viewModelScope.launch { tags.removeTag(documentId, name) }
    }

    fun setNote(note: String) {
        viewModelScope.launch { documents.setNote(documentId, note) }
    }

    fun moveToTrash() {
        viewModelScope.launch {
            documents.moveToTrash(documentId)
            events.send(DocumentEvent.Trashed(documentId))
        }
    }

    private fun work(kind: Busy, block: suspend () -> DocumentEvent?) {
        if (busy.value != null) return
        busy.value = kind
        viewModelScope.launch {
            val event = runCatching { block() }.getOrElse { DocumentEvent.Failed }
            busy.value = null
            event?.let { events.send(it) }
        }
    }
}
