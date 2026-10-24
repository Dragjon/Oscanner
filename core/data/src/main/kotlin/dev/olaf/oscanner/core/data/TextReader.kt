package dev.olaf.oscanner.core.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.olaf.oscanner.core.data.db.OscannerDatabase
import dev.olaf.oscanner.core.data.db.PageEntity
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.ocr.Languages
import dev.olaf.oscanner.core.ocr.OcrResult
import dev.olaf.oscanner.core.ocr.PaddleReader
import dev.olaf.oscanner.core.ocr.PageReader
import dev.olaf.oscanner.core.ocr.PrintSorter
import dev.olaf.oscanner.core.ocr.TextRecognizer
import dev.olaf.oscanner.core.pdf.PageLayout
import dev.olaf.oscanner.core.pdf.PdfWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration

/** Implemented by the Application so background workers can reach the data layer. */
interface DataContainerOwner {
    val container: DataContainer
}

/**
 * Reads the text on pages with Tesseract, in the background (PLAN.md §5.7). Each page's words
 * and boxes go to `ocr.json` beside its original, for searchable PDFs; the plain text goes to
 * the database, where FTS4 indexes it for search.
 */
class TextReader(
    private val context: Context,
    private val db: OscannerDatabase,
    private val settings: SettingsRepository,
    val languages: Languages,
) {

    private val _progress = MutableStateFlow<Map<Long, ReadProgress>>(emptyMap())
    /** How far reading has got, by document, while it runs. */
    val progress: StateFlow<Map<Long, ReadProgress>> = _progress.asStateFlow()

    private fun report(documentId: Long, progress: ReadProgress?) = _progress.update { all ->
        if (progress == null) all - documentId else all + (documentId to progress)
    }

    /**
     * Queues reading for [documentId] if the user wants text read automatically. A [delay] lets
     * someone finish cropping in Review first, so pages aren't read twice.
     */
    suspend fun scheduleIfWanted(documentId: Long, delay: Duration = Duration.ZERO) {
        if (settings.settings.first().autoReadText) schedule(documentId, delay)
    }

    fun schedule(documentId: Long, delay: Duration = Duration.ZERO) {
        val request = OneTimeWorkRequestBuilder<ReadTextWorker>()
            .setInputData(workDataOf(ReadTextWorker.DOCUMENT_ID to documentId))
            .setInitialDelay(delay)
            // Reading a dozen pages is heavy; wait if the battery is about to die.
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("read-text-$documentId", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Reads [documentId] now, because someone asked: ahead of anything queued for it, without
     * waiting for battery or a delay. Shows as started straight away.
     */
    fun readNow(documentId: Long) {
        report(documentId, ReadProgress(page = 0, pages = 0, fraction = 0f, preparing = true))
        val request = OneTimeWorkRequestBuilder<ReadTextWorker>()
            .setInputData(workDataOf(ReadTextWorker.DOCUMENT_ID to documentId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("read-text-$documentId", ExistingWorkPolicy.REPLACE, request)
    }

    /** Forgets what was read on a page, e.g. after it was re-cropped, so it's read again. */
    suspend fun forget(pageId: Long) {
        val page = db.pages().get(pageId) ?: return
        db.pages().setText(pageId, null)
        withContext(Dispatchers.IO) { ocrFile(page).delete() }
    }

    /** Reads every page of [documentId] that hasn't been read yet. */
    suspend fun readDocument(documentId: Long) {
        val pages = db.pages().unread(documentId)
        if (pages.isEmpty()) {
            report(documentId, null)
            return
        }
        val codes = settings.settings.first().textLanguages
        db.documents().setState(documentId, ProcessingState.ReadingText.name)
        report(documentId, ReadProgress(page = 0, pages = pages.size, fraction = 0f, preparing = true))
        try {
            withContext(Dispatchers.Default) {
                openReader(codes).use { recognizer ->
                    report(documentId, ReadProgress(page = 0, pages = pages.size, fraction = 0f))
                    for ((index, page) in pages.withIndex()) {
                        // A page deleted or re-cropped meanwhile is skipped; it'll be queued again.
                        val current = db.pages().get(page.id) ?: continue
                        if (current.editsJson != page.editsJson) continue
                        val onPage = { f: Float -> report(documentId, ReadProgress(index, pages.size, (index + f) / pages.size)) }
                        onPage(0f)
                        val result = runCatching { read(recognizer, page, onPage) }
                            .onFailure { Log.w(TAG, "Reading page ${page.id} failed", it) }
                            .getOrNull() ?: continue
                        // Changed while being read (say, an area blacked out): what we read is
                        // stale and may hold words that must not be kept. It's queued again.
                        if (db.pages().get(page.id)?.editsJson != page.editsJson) continue
                        withContext(Dispatchers.IO) { writeOcr(ocrFile(page), result) }
                        // Text in two columns reads down each column, not across both.
                        val words = result.words.map { PdfWord(it.text, it.left, it.top, it.right, it.bottom) }
                        db.pages().setText(page.id, PageLayout.columnText(words) ?: result.text)
                    }
                }
            }
        } finally {
            db.documents().setState(documentId, ProcessingState.Idle.name)
            report(documentId, null)
        }
        nameAfterHeading(documentId)
    }

    /** A document still called "Scan …" takes its first page's heading as its name, once read. */
    private suspend fun nameAfterHeading(documentId: Long) {
        if (db.documents().hasAutoTitle(documentId) != true) return
        val first = db.pages().forDocument(documentId).firstOrNull()?.takeIf { it.ocrText != null } ?: return
        val title = withContext(Dispatchers.Default) { DocumentTitles.fromWords(words(first)) } ?: return
        db.documents().autoName(documentId, title)
    }

    /**
     * Where the print is on a page as [edits] shape it, for telling print from handwriting: each
     * stretch of marks that look printed ([PrintSorter]), in any language. Takes a second or so.
     */
    suspend fun printedAreas(originalPath: String, edits: PageEdits): List<Area> = withContext(Dispatchers.Default) {
        val source = PageRenderer.loadUpright(originalPath, SOURCE_SIDE)
        // Handwriting must still be on the page to be told apart; in its own gray, as photographed.
        val image = PageRenderer.renderForOcr(
            source,
            edits.copy(removePenInk = false, printed = null, printByLook = false, erase = emptyList(), keep = emptyList()),
            PageFilter.Original,
        )
        source.recycle()
        try {
            PrintSorter(context).use { it.printAreas(image) }
        } finally {
            image.recycle()
        }
    }

    /** Words with boxes for a page's PDF text layer; empty if it hasn't been read. */
    fun words(page: PageEntity): List<PdfWord> =
        runCatching { readWords(ocrFile(page)) }.getOrDefault(emptyList())

    /**
     * PP-OCR for English, which reads photographed and faint print far better; Tesseract for
     * everything else, and as a fallback should the models fail to load.
     */
    private fun openReader(codes: List<String>): PageReader {
        if (codes.isEmpty() || codes.all { it == Languages.ENGLISH }) {
            runCatching { PaddleReader(context) }
                .onFailure { Log.w(TAG, "Text models failed to load; using Tesseract", it) }
                .getOrNull()
                ?.let { return it }
        }
        return TextRecognizer(languages, codes)
    }

    private fun read(recognizer: PageReader, page: PageEntity, onProgress: (Float) -> Unit): OcrResult {
        val source = PageRenderer.loadUpright(page.originalPath, SOURCE_SIDE)
        // Tesseract wants crisp black and white; the PP-OCR models were trained on photos, and
        // thresholding breaks up small or faint letters they could still have read in gray.
        val filter = if (recognizer is PaddleReader) PageFilter.Gray else PageFilter.Ink
        val image = PageRenderer.renderForOcr(source, EditsJson.read(page.editsJson), filter)
        source.recycle()
        // Rendering the page is a slice of the work too.
        onProgress(0.05f)
        return recognizer.read(image) { onProgress(0.05f + 0.95f * it) }.also { image.recycle() }
    }

    private fun ocrFile(page: PageEntity) = File(File(page.originalPath).parentFile, "ocr.json")

    private fun writeOcr(file: File, result: OcrResult) {
        val words = JSONArray()
        result.words.forEach { w ->
            words.put(JSONArray().put(w.text).put(w.left.toDouble()).put(w.top.toDouble()).put(w.right.toDouble()).put(w.bottom.toDouble()).put(w.confidence.toDouble()))
        }
        file.writeText(JSONObject().put("v", 1).put("text", result.text).put("words", words).toString())
    }

    private fun readWords(file: File): List<PdfWord> {
        if (!file.exists()) return emptyList()
        val words = JSONObject(file.readText()).optJSONArray("words") ?: return emptyList()
        return (0 until words.length()).mapNotNull { i ->
            val w = words.optJSONArray(i) ?: return@mapNotNull null
            PdfWord(w.optString(0), w.optDouble(1).toFloat(), w.optDouble(2).toFloat(), w.optDouble(3).toFloat(), w.optDouble(4).toFloat())
        }
    }

    private companion object {
        const val TAG = "TextReader"
        const val SOURCE_SIDE = 4000
        /** Tesseract's 0–100 word confidence above which a word is taken as print. */
        const val PRINT_CONFIDENCE = 75f
    }
}

/**
 * Where reading a document has got: [page] of [pages] (from 0), and [fraction] of the whole.
 * [preparing] while the reader loads its models, before any page is read.
 */
data class ReadProgress(val page: Int, val pages: Int, val fraction: Float, val preparing: Boolean = false)

/** Runs [TextReader.readDocument] for one document. */
class ReadTextWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(DOCUMENT_ID, -1)
        if (id < 0) return Result.failure()
        val owner = applicationContext as? DataContainerOwner ?: return Result.failure()
        return runCatching { owner.container.text.readDocument(id) }
            .fold(onSuccess = { Result.success() }, onFailure = { if (runAttemptCount < 2) Result.retry() else Result.failure() })
    }

    companion object {
        const val DOCUMENT_ID = "documentId"
    }
}
