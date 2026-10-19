package dev.olaf.oscanner.core.pdf

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

enum class PdfPageSize { Auto, A4, Letter }

/** A recognized word, with its box normalized to the page image (0…1, y down). */
data class PdfWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** One page: its JPEG, plus the words read from it when it should be searchable. */
data class PdfPage(val jpeg: File, val words: List<PdfWord> = emptyList())

/**
 * Writes a PDF with one JPEG per page, embedding each JPEG's bytes untouched (DCTDecode), so
 * the file is barely bigger than the images.
 *
 * Pages with [PdfPage.words] get an invisible text layer (render mode 3) in a glyphless font,
 * the same technique Tesseract's own PDF output uses: viewers can search, select and copy the
 * text, but nothing is drawn over the scan. Encryption comes with Phase 4.
 */
class JpegPdfWriter(private val pageSize: PdfPageSize = PdfPageSize.Auto) {

    fun write(jpegs: List<File>, out: OutputStream, title: String? = null) =
        writePages(jpegs.map { PdfPage(it) }, out, title)

    fun writePages(pages: List<PdfPage>, out: OutputStream, title: String? = null) {
        require(pages.isNotEmpty()) { "A PDF needs at least one page" }
        val pdf = CountingStream(out)
        val offsets = mutableListOf<Long>()
        fun obj(body: () -> Unit) {
            offsets += pdf.count
            pdf.ascii("${offsets.size} 0 obj\n")
            body()
            pdf.ascii("\nendobj\n")
        }

        pdf.ascii("%PDF-1.4\n")
        pdf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // Object numbers: 1 catalog, 2 page tree, 3 info, 4–9 the text font (only if any page
        // has words), then 3 per page (page, image, content).
        val searchable = pages.any { it.words.isNotEmpty() }
        val firstPageId = if (searchable) 10 else 4
        val pageIds = pages.indices.map { firstPageId + it * 3 }
        obj { pdf.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { pdf.ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>") }
        obj {
            val titleEntry = title?.let { " /Title ${literal(it)}" }.orEmpty()
            pdf.ascii("<< /Producer (Oscanner)$titleEntry >>")
        }
        if (searchable) writeFont(::obj, pdf)

        pages.forEachIndexed { index, page ->
            val bytes = page.jpeg.readBytes()
            val info = JpegInfo.read(bytes)
            val box = layout(info.width, info.height)
            val pageId = pageIds[index]
            obj {
                val font = if (page.words.isNotEmpty()) " /Font << /F1 4 0 R >>" else ""
                pdf.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(box.pageW)} ${fmt(box.pageH)}] " +
                        "/Resources << /XObject << /Im0 ${pageId + 1} 0 R >>$font >> /Contents ${pageId + 2} 0 R >>",
                )
            }
            obj {
                val colorSpace = when (info.components) {
                    1 -> "/DeviceGray"
                    4 -> "/DeviceCMYK"
                    else -> "/DeviceRGB"
                }
                pdf.ascii(
                    "<< /Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
                        "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n",
                )
                pdf.write(bytes)
                pdf.ascii("\nendstream")
            }
            obj {
                val content = buildString {
                    append("q ${fmt(box.w)} 0 0 ${fmt(box.h)} ${fmt(box.x)} ${fmt(box.y)} cm /Im0 Do Q")
                    if (page.words.isNotEmpty()) append(textLayer(page.words, box))
                }.toByteArray(Charsets.ISO_8859_1)
                pdf.ascii("<< /Length ${content.size} >>\nstream\n")
                pdf.write(content)
                pdf.ascii("\nendstream")
            }
        }

        val xref = pdf.count
        pdf.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.ascii(String.format(Locale.ROOT, "%010d 00000 n \n", it)) }
        pdf.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        pdf.flush()
    }

    /**
     * Objects 4–9: a Type0 font over Tesseract's one-glyph TrueType "GlyphLessFont". Every CID
     * maps to that blank glyph, and the identity ToUnicode map turns CIDs back into UTF-16, so
     * any text can be encoded without shipping real glyphs.
     */
    private fun writeFont(obj: (() -> Unit) -> Unit, pdf: CountingStream) {
        obj {
            pdf.ascii(
                "<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H " +
                    "/DescendantFonts [5 0 R] /ToUnicode 6 0 R >>",
            )
        }
        obj {
            pdf.ascii(
                "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont " +
                    "/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
                    "/FontDescriptor 7 0 R /DW $GLYPH_WIDTH /CIDToGIDMap 8 0 R >>",
            )
        }
        obj {
            val cmap = """
                /CIDInit /ProcSet findresource begin
                12 dict begin
                begincmap
                /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
                /CMapName /Adobe-Identity-UCS def
                /CMapType 2 def
                1 begincodespacerange
                <0000> <FFFF>
                endcodespacerange
                1 beginbfrange
                <0000> <FFFF> <0000>
                endbfrange
                endcmap
                CMapName currentdict /CMap defineresource pop
                end
                end
            """.trimIndent()
            pdf.ascii("<< /Length ${cmap.length} >>\nstream\n$cmap\nendstream")
        }
        obj {
            pdf.ascii(
                "<< /Type /FontDescriptor /FontName /GlyphLessFont /FontFile2 9 0 R " +
                    "/Ascent 1000 /Descent 0 /Flags 5 /FontBBox [0 0 $GLYPH_WIDTH 1000] /ItalicAngle 0 " +
                    "/CapHeight 1000 /StemV 80 >>",
            )
        }
        obj {
            // Every CID (2 bytes each, 65 536 of them) → glyph 1.
            val map = ByteArray(65_536 * 2) { if (it % 2 == 1) 1 else 0 }
            val packed = deflate(map)
            pdf.ascii("<< /Length ${packed.size} /Filter /FlateDecode >>\nstream\n")
            pdf.write(packed)
            pdf.ascii("\nendstream")
        }
        obj {
            val font = glyphlessFont
            pdf.ascii("<< /Length ${font.size} /Length1 ${font.size} >>\nstream\n")
            pdf.write(font)
            pdf.ascii("\nendstream")
        }
    }

    /** Invisible words, each stretched to its box so selection lines up with the scan. */
    private fun textLayer(words: List<PdfWord>, box: ImageBox): String = buildString {
        append("\nBT 3 Tr")
        for (word in alignLines(words)) {
            // A trailing space keeps words apart when the text is copied.
            val text = word.text.trim().takeIf { it.isNotEmpty() }?.plus(" ") ?: continue
            val units = text.toCharArray()
            val height = (word.bottom - word.top) * box.h
            val width = (word.right - word.left) * box.w
            if (height <= 0.0 || width <= 0.0) continue
            val natural = units.size * height * GLYPH_WIDTH / 1000.0
            val stretch = 100.0 * width / natural
            val x = box.x + word.left * box.w
            val y = box.y + (1.0 - word.bottom) * box.h
            append("\n/F1 ${fmt(height)} Tf ${fmt(stretch)} Tz 1 0 0 1 ${fmt(x)} ${fmt(y)} Tm <")
            units.forEach { append(String.format(Locale.ROOT, "%04X", it.code)) }
            append("> Tj")
        }
        append("\nET")
    }

    /**
     * Gives every word on a line the line's top and bottom. Word boxes alone differ by
     * descenders ("ipsum" sits lower than "Lorem"), and text extractors then read each word as
     * its own line. Words arrive in reading order; a new line starts when a word no longer
     * overlaps the current line vertically or jumps back to the left.
     */
    internal fun alignLines(words: List<PdfWord>): List<PdfWord> {
        val result = mutableListOf<PdfWord>()
        var line = mutableListOf<PdfWord>()
        fun flush() {
            if (line.isEmpty()) return
            val top = line.minOf { it.top }
            val bottom = line.maxOf { it.bottom }
            line.mapTo(result) { it.copy(top = top, bottom = bottom) }
            line = mutableListOf()
        }
        for (word in words) {
            val last = line.lastOrNull()
            if (last != null) {
                val lineTop = line.minOf { it.top }
                val lineBottom = line.maxOf { it.bottom }
                val center = (word.top + word.bottom) / 2
                if (center !in lineTop..lineBottom || word.left < last.left) flush()
            }
            line += word
        }
        flush()
        return result
    }

    private data class ImageBox(val pageW: Double, val pageH: Double, val x: Double, val y: Double, val w: Double, val h: Double)

    /** Page size in points and where the image sits on it, centered and fitted. */
    private fun layout(imageW: Int, imageH: Int): ImageBox {
        val portrait = imageH >= imageW
        val (pageW, pageH) = when (pageSize) {
            PdfPageSize.Auto -> {
                // Long side as long as A4's; the page takes the image's own shape.
                val long = A4_LONG
                if (portrait) long * imageW / imageH to long else long to long * imageH / imageW
            }
            PdfPageSize.A4 -> if (portrait) A4_SHORT to A4_LONG else A4_LONG to A4_SHORT
            PdfPageSize.Letter -> if (portrait) LETTER_SHORT to LETTER_LONG else LETTER_LONG to LETTER_SHORT
        }
        val scale = minOf(pageW / imageW, pageH / imageH)
        val w = imageW * scale
        val h = imageH * scale
        return ImageBox(pageW, pageH, (pageW - w) / 2, (pageH - h) / 2, w, h)
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    private fun literal(text: String): String {
        val ascii = text.all { it.code in 32..126 }
        if (ascii) {
            return "(" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        }
        // Non-ASCII titles as UTF-16BE with a byte-order mark, hex-encoded.
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) } + ">"
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        DeflaterOutputStream(buffer, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(bytes) }
        return buffer.toByteArray()
    }

    private class CountingStream(private val out: OutputStream) {
        var count = 0L
            private set

        fun write(bytes: ByteArray) {
            out.write(bytes)
            count += bytes.size
        }

        fun ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

        fun flush() = out.flush()
    }

    private companion object {
        const val A4_SHORT = 595.28
        const val A4_LONG = 841.89
        const val LETTER_SHORT = 612.0
        const val LETTER_LONG = 792.0
        /** Advance width of the blank glyph, in 1/1000 em. */
        const val GLYPH_WIDTH = 500
        /** Tesseract's GlyphLessFont (tessdata/pdf.ttf, Apache-2.0): one blank glyph. */
        val glyphlessFont: ByteArray by lazy {
            requireNotNull(JpegPdfWriter::class.java.getResourceAsStream("pdf.ttf")) { "pdf.ttf missing" }.use { it.readBytes() }
        }
    }
}

/** Width, height and channel count from a JPEG's start-of-frame marker. */
internal data class JpegInfo(val width: Int, val height: Int, val components: Int) {
    companion object {
        fun read(bytes: ByteArray): JpegInfo {
            fun u8(i: Int) = bytes[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            require(bytes.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8) { "Not a JPEG" }
            var i = 2
            while (i + 9 < bytes.size) {
                if (u8(i) != 0xFF) {
                    i++
                    continue
                }
                val marker = u8(i + 1)
                when {
                    marker == 0xFF -> i++
                    marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01 -> i += 2
                    // SOF0–SOF15 except DHT (C4), JPG (C8) and DAC (CC).
                    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC ->
                        return JpegInfo(width = u16(i + 7), height = u16(i + 5), components = u8(i + 9))
                    else -> i += 2 + u16(i + 2)
                }
            }
            error("JPEG has no frame header")
        }
    }
}
