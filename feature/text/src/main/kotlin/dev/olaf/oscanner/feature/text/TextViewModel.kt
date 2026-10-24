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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import android.util.Log
import dev.olaf.oscanner.core.translate.Paragraphs
import dev.olaf.oscanner.core.translate.TranslationLanguage
import dev.olaf.oscanner.core.translate.TranslationModels
import dev.olaf.oscanner.core.translate.Translator
import java.util.Locale
import java.io.File

/** Where translating has got, or what stopped it. */
sealed interface TranslationWork {
    data class Downloading(val fraction: Float) : TranslationWork
    data object Preparing : TranslationWork
    data class Translating(val page: Int, val pages: Int) : TranslationWork
    data object Failed : TranslationWork
}

/**
 * The document's text in another language: [from] and [to], the [languages] to choose from,
 * what's being done, and the pages translated so far ([pages], by page id). The page shows the
 * translation while [showing].
 */
data class TranslationUi(
    val from: String = TranslationModels.ENGLISH,
    val to: String = TranslationModels.ENGLISH,
    val languages: List<TranslationLanguage> = emptyList(),
    val work: TranslationWork? = null,
    val pages: Map<Long, String> = emptyMap(),
    val translatedFrom: String? = null,
    val translatedTo: String? = null,
    val showing: Boolean = false,
)

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
    private val models: TranslationModels,
    private val translator: Translator,
    /** The language a text is in, if it can tell: [LanguageGuess] with a context. */
    private val guessLanguage: (String) -> String?,
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

    private val _translation = MutableStateFlow(TranslationUi())
    val translation: StateFlow<TranslationUi> = _translation.asStateFlow()
    private var translating: Job? = null

    /**
     * Sets up the translate sheet: the text's language guessed, the target the one used last
     * (or the phone's language, or English).
     */
    fun prepareTranslation() {
        viewModelScope.launch {
            val text = allText()
            val known = models.codes
            val from = withContext(Dispatchers.Default) { guessLanguage(text) } ?: _translation.value.translatedFrom ?: TranslationModels.ENGLISH
            val phone = Locale.getDefault().language.takeIf { it in known }
            val languages = models.languages()
            // Then a language already on the phone, then any other.
            val to = (listOfNotNull(models.lastTarget, phone) + languages.filter { it.installed }.map { it.code } + TranslationModels.ENGLISH + languages.map { it.code })
                .first { it != from }
            _translation.update { it.copy(from = from, to = to, languages = languages, work = it.work.takeUnless { w -> w is TranslationWork.Failed }) }
        }
    }

    fun setTranslateFrom(code: String) = _translation.update { it.copy(from = code) }

    fun setTranslateTo(code: String) = _translation.update { it.copy(to = code) }

    /** Downloads what the pair needs, then translates every page, showing each as it's done. */
    fun translate() {
        val (from, to) = _translation.value.let { it.from to it.to }
        if (from == to || translating?.isActive == true) return
        models.lastTarget = to
        translating = viewModelScope.launch {
            try {
                val missing = listOf(from, to).distinct().filterNot(models::isInstalled)
                missing.forEachIndexed { i, code ->
                    models.download(code) { f -> _translation.update { it.copy(work = TranslationWork.Downloading((i + f) / missing.size)) } }
                }
                _translation.update { it.copy(work = TranslationWork.Preparing, languages = models.languages(), pages = emptyMap(), translatedFrom = from, translatedTo = to, showing = true) }
                val pages = state.value.pages.filter { !it.text.isNullOrBlank() }
                pages.forEachIndexed { index, page ->
                    // The first page also waits for the engine to start: that shows as preparing.
                    if (index > 0) _translation.update { it.copy(work = TranslationWork.Translating(index + 1, pages.size)) }
                    val paragraphs = Paragraphs.of(page.text.orEmpty())
                    val translated = translator.translate(from, to, paragraphs).joinToString("\n")
                    _translation.update { it.copy(pages = it.pages + (page.id to translated)) }
                }
                _translation.update { it.copy(work = null) }
            } catch (e: CancellationException) {
                _translation.update { it.copy(work = null) }
                throw e
            } catch (e: Exception) {
                Log.w("TextViewModel", "Translation failed", e)
                _translation.update { it.copy(work = TranslationWork.Failed) }
            }
        }
    }

    fun cancelTranslation() {
        translating?.cancel()
    }

    /** Frees a downloaded language's space; a translation already shown stays. */
    fun removeLanguage(code: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { models.delete(code) }
            _translation.update { it.copy(languages = models.languages()) }
        }
    }

    // The engine holds its models in memory; it starts again when next needed.
    override fun onCleared() = translator.close()

    /** Between the page's own text and its translation. */
    fun showTranslation(show: Boolean) = _translation.update { it.copy(showing = show) }

    /** What the page shows: its translation while that's shown and done, else its own text. */
    fun shownText(page: Page): String? = _translation.value.let { t -> t.pages[page.id]?.takeIf { t.showing } } ?: page.text

    /** Every page as shown, pages separated by a blank line. */
    fun allShownText(): String = state.value.pages.mapNotNull { shownText(it)?.takeIf(String::isNotBlank) }.joinToString("\n\n")

    fun exportText() {
        viewModelScope.launch { runCatching { store.exportText(documentId) }.onSuccess { exports.send(it to "text/plain") } }
    }

    fun exportWord() {
        viewModelScope.launch { runCatching { store.exportDocx(documentId) }.onSuccess { exports.send(it to DOCX) } }
    }

    fun exportSpreadsheet() {
        viewModelScope.launch { runCatching { store.exportXlsx(documentId) }.onSuccess { exports.send(it to XLSX) } }
    }

    private companion object {
        const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
