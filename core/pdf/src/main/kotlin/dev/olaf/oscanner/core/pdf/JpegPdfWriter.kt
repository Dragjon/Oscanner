package dev.olaf.oscanner.core.pdf

import java.io.File
import java.io.OutputStream

enum class PdfPageSize { Auto, A4, Letter }

/**
 * Writes a PDF with one JPEG per page, embedding each JPEG's bytes untouched (DCTDecode), so
 * the file is barely bigger than the images. Text layers and encryption come with PdfBox in
 * Phase 2/4; this covers plain image PDFs with no dependency.
 */
class JpegPdfWriter(private val pageSize: PdfPageSize = PdfPageSize.Auto) {

    fun write(jpegs: List<File>, out: OutputStream, title: String? = null) {
        require(jpegs.isNotEmpty()) { "A PDF needs at least one page" }
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

        // Object numbers: 1 catalog, 2 page tree, 3 info, then 3 per page (page, image, content).
        val pageIds = jpegs.indices.map { 4 + it * 3 }
        obj { pdf.ascii("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { pdf.ascii("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${jpegs.size} >>") }
        obj {
            val titleEntry = title?.let { " /Title ${literal(it)}" }.orEmpty()
            pdf.ascii("<< /Producer (Oscanner)$titleEntry >>")
        }

        jpegs.forEachIndexed { index, file ->
            val bytes = file.readBytes()
            val info = JpegInfo.read(bytes)
            val (pageW, pageH, placement) = layout(info.width, info.height)
            val pageId = pageIds[index]
            obj {
                pdf.ascii(
                    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}] " +
                        "/Resources << /XObject << /Im0 ${pageId + 1} 0 R >> >> /Contents ${pageId + 2} 0 R >>",
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
                val content = "q $placement cm /Im0 Do Q"
                pdf.ascii("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            }
        }

        val xref = pdf.count
        pdf.ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { pdf.ascii("%010d 00000 n \n".format(it)) }
        pdf.ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
        pdf.flush()
    }

    /** Page size in points and the image's transform ("w 0 0 h x y"), centered and fitted. */
    private fun layout(imageW: Int, imageH: Int): Triple<Double, Double, String> {
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
        val x = (pageW - w) / 2
        val y = (pageH - h) / 2
        return Triple(pageW, pageH, "${fmt(w)} 0 0 ${fmt(h)} ${fmt(x)} ${fmt(y)}")
    }

    private fun fmt(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

    private fun literal(text: String): String {
        val ascii = text.all { it.code in 32..126 }
        if (ascii) {
            return "(" + text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
        }
        // Non-ASCII titles as UTF-16BE with a byte-order mark, hex-encoded.
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF) } + ">"
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
