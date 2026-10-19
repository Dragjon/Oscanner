package dev.olaf.oscanner.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class JpegPdfWriterTest {

    @get:Rule val temp = TemporaryFolder()

    /** Just enough JPEG for the writer: SOI, a JFIF APP0, a baseline frame header, EOI. */
    private fun jpeg(name: String, w: Int, h: Int): File {
        val app0 = intArrayOf(0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
        val sof0 = intArrayOf(
            0xFF, 0xC0, 0x00, 0x11, 0x08, h shr 8, h and 0xFF, w shr 8, w and 0xFF, 0x03,
            0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        )
        val bytes = intArrayOf(0xFF, 0xD8) + app0 + sof0 + intArrayOf(0xFF, 0xD9)
        return temp.newFile(name).apply { writeBytes(ByteArray(bytes.size) { bytes[it].toByte() }) }
    }

    @Test
    fun readsJpegFrameHeader() {
        val info = JpegInfo.read(jpeg("a.jpg", 321, 123).readBytes())
        assertEquals(JpegInfo(321, 123, 3), info)
    }

    @Test
    fun writesWellFormedPdfWithEveryPage() {
        val pages = listOf(jpeg("1.jpg", 200, 300), jpeg("2.jpg", 300, 200))
        val out = ByteArrayOutputStream()
        JpegPdfWriter().write(pages, out, title = "Lease (copy)")
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)

        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/Title (Lease \\(copy\\))"))
        assertEquals(2, Regex("/Subtype /Image").findAll(text).count())

        // Every xref offset must point at the start of its "n 0 obj" line.
        val xrefAt = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") || it.endsWith(" n") }
        entries.forEachIndexed { index, line ->
            val offset = line.take(10).toInt()
            assertTrue("object ${index + 1}", text.startsWith("${index + 1} 0 obj", offset))
        }
    }

    @Test
    fun autoPageTakesImageShape() {
        val out = ByteArrayOutputStream()
        JpegPdfWriter(PdfPageSize.Auto).write(listOf(jpeg("w.jpg", 400, 200)), out)
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(text, text.contains("/MediaBox [0 0 841.89 420.95]") || text.contains("/MediaBox [0 0 841.89 420.94]"))
    }
}
