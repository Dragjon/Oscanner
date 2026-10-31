package dev.olaf.oscanner.feature.capture

import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** A decoded QR code or barcode. */
data class ScannedCode(val text: String, val format: String) {

    val kind: CodeKind
        get() = when {
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> CodeKind.Link
            text.startsWith("WIFI:", ignoreCase = true) -> CodeKind.Wifi
            format in PRODUCT_FORMATS -> CodeKind.Product
            else -> CodeKind.Text
        }

    /** Network name and password from a `WIFI:T:WPA;S:name;P:secret;;` code. */
    val wifi: Pair<String, String?>?
        get() {
            if (kind != CodeKind.Wifi) return null
            val fields = parseWifi(text.substring(5))
            return (fields["S"] ?: return null) to fields["P"]
        }

    private companion object {
        val PRODUCT_FORMATS = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E")

        /** Splits on unescaped `;`, then `key:value`; backslash escapes `\;`, `\:`, `\\`. */
        fun parseWifi(body: String): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            val current = StringBuilder()
            var escaped = false
            for (c in body) {
                when {
                    escaped -> {
                        current.append(c)
                        escaped = false
                    }
                    c == '\\' -> escaped = true
                    c == ';' -> {
                        val part = current.toString()
                        val colon = part.indexOf(':')
                        if (colon > 0) fields[part.substring(0, colon)] = part.substring(colon + 1)
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            return fields
        }
    }
}

enum class CodeKind { Link, Wifi, Product, Text }

/**
 * ZXing over the luminance plane of preview frames. One per analysis thread. A barcode is read
 * along the frame's rows, and the camera's frames lie on their side: one held level in front of
 * a phone held upright runs down the frame, so a frame with nothing found is tried a quarter
 * turn round too.
 */
internal class CodeReader {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.AZTEC, BarcodeFormat.PDF_417,
                    BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                    BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF,
                ),
            ),
        )
    }
    private var bytes = ByteArray(0)
    private var turned = ByteArray(0)

    fun read(image: ImageProxy): ScannedCode? {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val size = buffer.remaining()
        if (bytes.size != size) bytes = ByteArray(size)
        buffer.get(bytes)
        return read(bytes, image.width, image.height, plane.rowStride)
    }

    /** Decodes an 8-bit luminance image laid out [rowStride] bytes per row, either way round. */
    fun read(luminance: ByteArray, width: Int, height: Int, rowStride: Int = width): ScannedCode? =
        decode(luminance, width, height, rowStride) ?: run {
            // A quarter turn clockwise: the frame's columns become rows.
            if (turned.size != width * height) turned = ByteArray(width * height)
            for (y in 0 until height) {
                val row = y * rowStride
                for (x in 0 until width) turned[x * height + (height - 1 - y)] = luminance[row + x]
            }
            decode(turned, height, width, height)
        }

    private fun decode(luminance: ByteArray, width: Int, height: Int, rowStride: Int): ScannedCode? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return try {
            val result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            ScannedCode(result.text, result.barcodeFormat.name)
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
