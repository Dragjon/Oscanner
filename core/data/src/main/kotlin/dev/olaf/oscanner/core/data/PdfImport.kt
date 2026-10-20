package dev.olaf.oscanner.core.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The pages of a PDF someone already has, as pictures, so they can be signed, marked up,
 * cleaned, merged and shared like scans. Uses the PDF renderer built into Android.
 */
internal object PdfImport {

    /**
     * Renders every page of the PDF at [uri] into [dir] as JPEGs, in order. Throws when the file
     * can't be opened: not a PDF, damaged, or locked with a password.
     */
    fun pages(context: Context, uri: Uri, dir: File): List<File> {
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
                        file
                    }
                }
            }
        }
    }

    private const val DPI = 200f
    private const val MAX_SIDE = 3300f
    private const val MIN_SIDE = 1600f
    private const val QUALITY = 92
}
