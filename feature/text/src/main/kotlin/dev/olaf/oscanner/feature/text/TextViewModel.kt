package dev.olaf.oscanner.feature.text

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.olaf.oscanner.core.data.DocumentRepository
import dev.olaf.oscanner.core.data.PageStore
import dev.olaf.oscanner.core.data.ReadProgress
import dev.olaf.oscanner.core.model.Document
import dev.olaf.oscanner.core.model.Page
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class TextUiState(
    val loading: Boolean = true,
    val document: Document? = null,
    val pages: List<Page> = emptyList(),
    /** How far reading has got while it runs; null otherwise. */
    val progress: ReadProgress? = null,
)

class TextViewModel(
    documents: DocumentRepository,
    private val store: PageStore,
    val documentId: Long,
) : ViewModel() {

    val state: StateFlow<TextUiState> = combine(
        documents.observeDocument(documentId),
        store.observePages(documentId),
        store.readProgress(documentId),
    ) { document, pages, progress -> TextUiState(loading = false, document = document, pages = pages, progress = progress) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TextUiState())

    /** A finished export and the MIME type to share it as. */
    private val exports = Channel<Pair<File, String>>(Channel.BUFFERED)
    val exportedFiles: Flow<Pair<File, String>> = exports.receiveAsFlow()

    /** Every page's text, pages separated by a blank line. */
    fun allText(): String = state.value.pages.mapNotNull { it.text?.takeIf(String::isNotBlank) }.joinToString("\n\n")

    fun readNow() = store.readNow(documentId)

    fun save(page: Page, text: String) {
        viewModelScope.launch { store.setText(page.id, text) }
    }

    fun exportText() {
        viewModelScope.launch { runCatching { store.exportText(documentId) }.onSuccess { exports.send(it to "text/plain") } }
    }

    fun exportWord() {
        viewModelScope.launch { runCatching { store.exportDocx(documentId) }.onSuccess { exports.send(it to DOCX) } }
    }

    private companion object {
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
