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
import dev.olaf.oscanner.core.model.ProcessingState
import dev.olaf.oscanner.core.ocr.Languages
import dev.olaf.oscanner.core.ocr.OcrResult
import dev.olaf.oscanner.core.ocr.TextRecognizer
import dev.olaf.oscanner.core.pdf.PdfWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    /** Forgets what was read on a page, e.g. after it was re-cropped, so it's read again. */
    suspend fun forget(pageId: Long) {
        val page = db.pages().get(pageId) ?: return
        db.pages().setText(pageId, null)
        withContext(Dispatchers.IO) { ocrFile(page).delete() }
    }

    /** Reads every page of [documentId] that hasn't been read yet. */
    suspend fun readDocument(documentId: Long) {
        val pages = db.pages().unread(documentId)
        if (pages.isEmpty()) return
        val codes = settings.settings.first().textLanguages
        db.documents().setState(documentId, ProcessingState.ReadingText.name)
        try {
            withContext(Dispatchers.Default) {
                TextRecognizer(languages, codes).use { recognizer ->
                    for (page in pages) {
                        // A page deleted or re-cropped meanwhile is skipped; it'll be queued again.
                        val current = db.pages().get(page.id) ?: continue
                        if (current.editsJson != page.editsJson) continue
                        val result = runCatching { read(recognizer, page) }
                            .onFailure { Log.w(TAG, "Reading page ${page.id} failed", it) }
                            .getOrNull() ?: continue
                        // Changed while being read (say, an area blacked out): what we read is
                        // stale and may hold words that must not be kept. It's queued again.
                        if (db.pages().get(page.id)?.editsJson != page.editsJson) continue
                        withContext(Dispatchers.IO) { writeOcr(ocrFile(page), result) }
                        db.pages().setText(page.id, result.text)
                    }
                }
            }
        } finally {
            db.documents().setState(documentId, ProcessingState.Idle.name)
        }
    }

    /** Words with boxes for a page's PDF text layer; empty if it hasn't been read. */
    fun words(page: PageEntity): List<PdfWord> =
        runCatching { readWords(ocrFile(page)) }.getOrDefault(emptyList())

    private fun read(recognizer: TextRecognizer, page: PageEntity): OcrResult {
        val source = PageRenderer.loadUpright(page.originalPath, SOURCE_SIDE)
        val image = PageRenderer.renderForOcr(source, EditsJson.read(page.editsJson))
        source.recycle()
        return recognizer.read(image).also { image.recycle() }
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
    }
}

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
