package dev.olaf.oscanner.core.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

/** One recognized word. Box edges are normalized to the image that was read (0…1). */
data class OcrWord(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float,
)

data class OcrResult(
    /** Reading-order text with line breaks, as Tesseract laid it out. */
    val text: String,
    val words: List<OcrWord>,
)

/**
 * Tesseract on a cleaned page image: every language it has data for. English pages go to
 * [PaddleReader] instead; Tesseract's word confidence also tells print from handwriting.
 * Not thread-safe and holds native memory: create one per job, call [close] when done.
 */
class TextRecognizer(languages: Languages, codes: List<String>) : PageReader {

    private val api = TessBaseAPI()

    init {
        val wanted = codes.filter(languages::isInstalled).ifEmpty { listOf(Languages.ENGLISH) }
        languages.ensureBundled()
        check(api.init(languages.dataPath, wanted.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) {
            "Tesseract failed to start for ${wanted.joinToString("+")}"
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        // Our renders are about 240–300 dpi on a full page; saying so avoids a guess per image.
        api.setVariable("user_defined_dpi", "300")
    }

    override fun read(image: Bitmap): OcrResult {
        api.setImage(image)
        val text = api.utF8Text.orEmpty().trim()
        val words = mutableListOf<OcrWord>()
        val iterator = api.resultIterator
        if (iterator != null) {
            val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
            iterator.begin()
            do {
                val word = iterator.getUTF8Text(level)?.trim().orEmpty()
                if (word.isEmpty()) continue
                val box = iterator.getBoundingRect(level)
                words += OcrWord(
                    text = word,
                    left = box.left.toFloat() / image.width,
                    top = box.top.toFloat() / image.height,
                    right = box.right.toFloat() / image.width,
                    bottom = box.bottom.toFloat() / image.height,
                    confidence = iterator.confidence(level),
                )
            } while (iterator.next(level))
            iterator.delete()
        }
        api.clear()
        return OcrResult(text, words)
    }

    /** Asks a running [read] to give up early. Safe from another thread. */
    fun stop() = api.stop()

    override fun close() = api.recycle()
}
