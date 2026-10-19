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
    fun searchablePageCarriesInvisibleUnicodeText() {
        val words = listOf(
            PdfWord("Électricité", 0.1f, 0.1f, 0.5f, 0.15f),
            PdfWord("84.20", 0.6f, 0.8f, 0.8f, 0.85f),
        )
        val out = ByteArrayOutputStream()
        JpegPdfWriter().writePages(listOf(PdfPage(jpeg("t.jpg", 1000, 1400), words), PdfPage(jpeg("u.jpg", 1000, 1400))), out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)

        assertTrue(text.contains("/Subtype /Type0"))
        assertTrue(text.contains("3 Tr"))
        // "É" is U+00C9; a trailing space separates words.
        assertTrue(text.contains("<00C9006C00650063007400720069006300690074" + "00E90020> Tj"))
        // The font program is embedded once.
        assertEquals(1, Regex("/FontFile2").findAll(text).count())
        // Every xref offset must still point at its object.
        val xrefAt = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }.forEachIndexed { index, line ->
            assertTrue("object ${index + 1}", text.startsWith("${index + 1} 0 obj", line.take(10).toInt()))
        }
        // Kept for a manual check with another PDF reader (pypdf) in the build folder.
        File("build/test-output").apply { mkdirs() }.resolve("searchable.pdf").writeBytes(bytes)
    }

    @Test
    fun wordsOnALineShareItsBaseline() {
        val words = listOf(
            PdfWord("Lorem", 0.10f, 0.100f, 0.20f, 0.120f),
            PdfWord("ipsum", 0.22f, 0.102f, 0.32f, 0.126f),
            PdfWord("next", 0.10f, 0.150f, 0.18f, 0.170f),
        )
        val aligned = JpegPdfWriter().alignLines(words)
        assertEquals(0.100f, aligned[0].top)
        assertEquals(0.126f, aligned[0].bottom)
        assertEquals(aligned[0].bottom, aligned[1].bottom)
        assertEquals(0.170f, aligned[2].bottom)
    }

    @Test
    fun cardsPrintTwoPerA4SheetAtTrueSize() {
        val out = ByteArrayOutputStream()
        val cards = listOf(PdfPage(jpeg("f.jpg", 1011, 638)), PdfPage(jpeg("b.jpg", 1011, 638)), PdfPage(jpeg("c.jpg", 1011, 638)))
        JpegPdfWriter(PdfPageSize.A4).writeCards(cards, out)
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        // Three cards: two sheets, three images.
        assertTrue(text.contains("/Count 2"))
        assertEquals(3, Regex("/Subtype /Image").findAll(text).count())
        assertTrue(text.contains("/MediaBox [0 0 595.28 841.89]"))
        // 85.60 mm = 242.65 pt; 53.98 mm = 153.01 pt.
        assertTrue(text, text.contains("q 242.65 0 0 153.01 "))
        val xrefAt = text.substringAfterLast("startxref\n").trim().removeSuffix("%%EOF").trim().toInt()
        text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }.forEachIndexed { index, line ->
            assertTrue("object ${index + 1}", text.startsWith("${index + 1} 0 obj", line.take(10).toInt()))
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
