package dev.olaf.oscanner.feature.capture

import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeReaderTest {

    /** A QR code drawn into a gray frame with padding, like a camera's luminance plane. */
    private fun frame(text: String, size: Int = 480, stride: Int = 512): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        val bytes = ByteArray(stride * size) { 200.toByte() }
        val offset = (size - 300) / 2
        for (y in 0 until 300) for (x in 0 until 300) {
            if (matrix[x, y]) bytes[(y + offset) * stride + x + offset] = 20
        }
        return bytes
    }

    @Test
    fun readsQrFromLuminanceWithRowPadding() {
        val code = CodeReader().read(frame("https://example.org/a?b=1"), 480, 480, rowStride = 512)
        assertEquals(ScannedCode("https://example.org/a?b=1", "QR_CODE"), code)
        assertEquals(CodeKind.Link, code!!.kind)
    }

    @Test
    fun blankFrameHasNoCode() {
        assertNull(CodeReader().read(ByteArray(480 * 480) { 128.toByte() }, 480, 480))
    }

    /** An EAN-13 barcode drawn into a frame; [upright] bars run across the frame, as the camera sees one held level in a portrait phone. */
    private fun barcode(digits: String, upright: Boolean, size: Int = 480): ByteArray {
        val matrix = EAN13Writer().encode(digits, BarcodeFormat.EAN_13, 300, 120)
        val bytes = ByteArray(size * size) { 200.toByte() }
        val offset = (size - 300) / 2
        for (y in 0 until 120) for (x in 0 until 300) {
            if (!matrix[x, y]) continue
            val (fx, fy) = if (upright) (size / 2 - 60 + y) to (offset + x) else (offset + x) to (size / 2 - 60 + y)
            bytes[fy * size + fx] = 20
        }
        return bytes
    }

    @Test
    fun readsBarcodesEitherWayRound() {
        for (upright in listOf(false, true)) {
            val code = CodeReader().read(barcode("5012345678900", upright), 480, 480)
            assertEquals("upright=$upright", ScannedCode("5012345678900", "EAN_13"), code)
            assertEquals(CodeKind.Product, code!!.kind)
        }
    }
}
