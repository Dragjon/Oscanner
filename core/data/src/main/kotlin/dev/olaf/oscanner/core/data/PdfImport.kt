package dev.olaf.oscanner.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.models.selection.SelectionBoundary
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import dev.olaf.oscanner.core.ocr.OcrResult
import dev.olaf.oscanner.core.ocr.OcrWord
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** A page of an imported PDF: its picture, and its own text when the PDF has it. */
internal class ImportedPage(val file: File, val text: OcrResult?)

/**
 * The pages of a PDF someone already has, as pictures, so they can be signed, marked up,
 * cleaned, merged and shared like scans. Uses the PDF renderer built into Android.
 */
internal object PdfImport {

    /**
     * Renders every page of the PDF at [uri] into [dir] as JPEGs, in order. Throws when the file
     * can't be opened: not a PDF, damaged, or locked with a password.
     */
    fun pages(context: Context, uri: Uri, dir: File): List<ImportedPage> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open $uri")
        return descriptor.use { fd ->
            PdfRenderer(fd).use { renderer ->
                List(renderer.pageCount) { index ->
                    renderer.openPage(index).use { page ->
                        // Points are 1/72 inch: render at 200 dpi, within a sensible size either way.
                        val scale = (DPI / 72f).let { s ->
                            val long = max(page.width, page.height) * s
                            when {
                                long > MAX_SIDE -> MAX_SIDE / max(page.width, page.height).toFloat()
                                long < MIN_SIDE -> MIN_SIDE / max(page.width, page.height).toFloat()
                                else -> s
                            }
                        }
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).roundToInt().coerceAtLeast(1),
                            (page.height * scale).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        // PDFs draw on a transparent page; paper is white.
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        val file = File(dir, "${UUID.randomUUID()}.jpg")
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                        bitmap.recycle()
                        val text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            runCatching { text(page) }.onFailure { Log.w(TAG, "Can't take page $index's text", it) }.getOrNull()
                        } else {
                            null
                        }
                        ImportedPage(file, text)
                    }
                }
            }
        }
    }

    /**
     * The page's own text, word by word with where each sits (as fractions of the page), so it
     * needn't be read from the picture: exact, and instant. Null when the page has too little
     * to be worth it (a scan, or a scan under a stamped line, is better read whole) or the text
     * doesn't come out as letters, as with fonts that don't say which letter each shape is.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun text(page: PdfRenderer.Page): OcrResult? {
        // Selections count characters through the page's text as these give it, end to end.
        val all = page.textContents.joinToString("") { it.text }
        val tokens = WORD.findAll(all).toList()
        if (tokens.size < MIN_WORDS || !readable(all)) return null
        val width = page.width.toFloat()
        val height = page.height.toFloat()
        var misses = 0
        val words = tokens.mapNotNull { token ->
            val selected = page.selectContent(SelectionBoundary(token.range.first), SelectionBoundary(token.range.last + 1))
                ?.selectedTextContents.orEmpty()
            val boxes = selected.flatMap { it.bounds }
            if (boxes.isEmpty() || selected.joinToString("") { it.text }.trim() != token.value) {
                misses++
                return@mapNotNull null
            }
            OcrWord(
                token.value,
                (boxes.minOf { it.left } / width).coerceIn(0f, 1f),
                (boxes.minOf { it.top } / height).coerceIn(0f, 1f),
                (boxes.maxOf { it.right } / width).coerceIn(0f, 1f),
                (boxes.maxOf { it.bottom } / height).coerceIn(0f, 1f),
                confidence = 100f,
            )
        }
        // Words that can't be placed mean the text and the page don't agree; reading is safer.
        if (misses > tokens.size * MAX_MISSES) return null
        val text = all.replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trimEnd() }.trim()
        return OcrResult(text, words)
    }

    /**
     * Text a person could read: mostly letters, digits and punctuation, not the stand-in or
     * private-use characters that fonts without a letter map come out as.
     */
    internal fun readable(text: String): Boolean {
        val marks = text.filterNot { it.isWhitespace() }
        if (marks.isEmpty()) return false
        val odd = marks.count { it == '�' || it.isISOControl() || Character.getType(it) == Character.PRIVATE_USE.toInt() }
        return odd <= marks.length * MAX_ODD
    }

    private const val TAG = "PdfImport"
    private const val DPI = 200f
    private const val MAX_SIDE = 3300f
    private const val MIN_SIDE = 1600f
    private const val QUALITY = 92
    private val WORD = Regex("\\S+")
    /** Fewer words than this and the page is read from its picture, which finds them all. */
    private const val MIN_WORDS = 20
    /** Share of words that may fail to place before the page is read instead. */
    private const val MAX_MISSES = 0.1
    private const val MAX_ODD = 0.05
}
