package dev.olaf.oscanner.feature.capture

import com.google.zxing.BarcodeFormat
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
}
