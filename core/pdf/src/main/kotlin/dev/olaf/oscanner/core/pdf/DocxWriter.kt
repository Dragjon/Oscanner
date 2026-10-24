package dev.olaf.oscanner.core.pdf

import java.io.OutputStream
import kotlin.math.min
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Word document (.docx, Office Open XML) of recognized text: the title, then each page's
 * paragraphs, headings and pictures in reading order (see [PageLayout]), with a page break
 * between scanned pages. Written by hand like the PDF, so there's no dependency: a .docx is a
 * zip of a few XML parts.
 */
object DocxWriter {

    /** Plain text, one string per page: paragraphs only. */
    fun write(title: String, pages: List<String>, out: OutputStream) =
        writeLayout(title, pages.map { page -> paragraphs(page).map { LayoutParagraph(it, ParagraphStyle.Body, false, 1f, 0f) } }, out)

    /** Each page's blocks, in reading order. */
    fun writeLayout(title: String, pages: List<List<LayoutBlock>>, out: OutputStream) {
        val pictures = pages.flatten().filterIsInstance<LayoutPicture>()
        ZipOutputStream(out).use { zip ->
            fun part(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun part(name: String, xml: String) = part(name, xml.toByteArray(Charsets.UTF_8))
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("word/_rels/document.xml.rels", documentRels(pictures.size))
            part("word/styles.xml", STYLES)
            part("docProps/core.xml", core(title))
            part("word/document.xml", document(title, pages, pictures))
            pictures.forEachIndexed { i, picture -> part("word/media/image${i + 1}.jpeg", picture.jpeg) }
        }
    }

    /**
     * Turns OCR text into paragraphs: blank lines separate them; lines inside one are joined
     * with spaces, and a word broken across lines with a hyphen is put back together.
     */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n")
            .split(Regex("\n\\s*\n"))
            .map { block ->
                block.lines().map(String::trim).filter(String::isNotEmpty).fold("") { acc, line ->
                    when {
                        acc.isEmpty() -> line
                        acc.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> acc.dropLast(1) + line
                        else -> "$acc $line"
                    }
                }
            }
            .filter(String::isNotEmpty)

    private fun document(title: String, pages: List<List<LayoutBlock>>, pictures: List<LayoutPicture>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="$W" xmlns:r="$R" xmlns:wp="$WP" xmlns:a="$A" xmlns:pic="$PIC"><w:body>""")
        append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t xml:space="preserve">${escape(title)}</w:t></w:r></w:p>""")
        // Parts of a page set in two columns become sections of two columns, each started
        // without a break of its own; the paragraph that ends a section carries its settings.
        var columns = 1
        var written = false
        var lastColumn = 0
        fun endSection() {
            if (written) append("""<w:p><w:pPr>${section(columns)}</w:pPr></w:p>""")
            written = false
        }
        pages.forEachIndexed { index, page ->
            if (index > 0) {
                if (columns == 2) { endSection(); columns = 1 }
                append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            }
            for (block in page) {
                val wanted = if (block.column == 0) 1 else 2
                if (wanted != columns) {
                    endSection()
                    columns = wanted
                    lastColumn = 0
                }
                if (block.column == 2 && lastColumn == 1) append("""<w:p><w:r><w:br w:type="column"/></w:r></w:p>""")
                lastColumn = block.column
                when (block) {
                    is LayoutParagraph -> append(paragraph(block))
                    is LayoutPicture -> append(picture(block, pictures.indexOf(block) + 1))
                }
                written = true
            }
        }
        append(section(columns))
        append("</w:body></w:document>")
    }

    /**
     * A section's settings: it runs on from the one before on the same page, in [columns]
     * columns 1 cm apart. Letter/A4-neutral margins of 2 cm; the page size is left to the
     * reader's default.
     */
    private fun section(columns: Int) =
        """<w:sectPr><w:type w:val="continuous"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/>""" +
            """<w:cols w:num="$columns" w:space="567"/></w:sectPr>"""

    private fun paragraph(p: LayoutParagraph): String {
        val style = when (p.style) {
            ParagraphStyle.Heading1 -> """<w:pStyle w:val="Heading1"/>"""
            ParagraphStyle.Heading2 -> """<w:pStyle w:val="Heading2"/>"""
            ParagraphStyle.Body -> ""
        }
        val align = if (p.centered) """<w:jc w:val="center"/>""" else ""
        // Body text keeps the style's size; a paragraph in clearly bigger or smaller print
        // than the page's body text gets its own, in half-points.
        val size = if (p.style == ParagraphStyle.Body && (p.size > 1.2f || p.size < 0.8f)) {
            """<w:rPr><w:sz w:val="${(BODY_HALF_POINTS * p.size).toInt().coerceIn(14, 56)}"/></w:rPr>"""
        } else {
            ""
        }
        val pPr = if (style.isEmpty() && align.isEmpty()) "" else "<w:pPr>$style$align</w:pPr>"
        return """<w:p>$pPr<w:r>$size<w:t xml:space="preserve">${escape(p.text)}</w:t></w:r></w:p>"""
    }

    /** An inline picture as wide as it was on the page, sized in EMU (914,400 to the inch). */
    private fun picture(p: LayoutPicture, n: Int): String {
        // In a column, no wider than the column.
        val most = if (p.column == 0) TEXT_WIDTH_EMU else (TEXT_WIDTH_EMU - COLUMN_SPACE_EMU) / 2
        val cx = min((TEXT_WIDTH_EMU * p.widthShare.coerceIn(0.05f, 1f)).toLong(), most)
        val cy = cx * p.pixelHeight / p.pixelWidth.coerceAtLeast(1)
        val align = if (p.widthShare < 0.9f) """<w:pPr><w:jc w:val="center"/></w:pPr>""" else ""
        return """<w:p>$align<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>""" +
            """<wp:docPr id="$n" name="Picture $n"/><a:graphic><a:graphicData uri="$PIC"><pic:pic><pic:nvPicPr>""" +
            """<pic:cNvPr id="$n" name="image$n.jpeg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdImg$n"/>""" +
            """<a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun documentRels(pictures: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        for (n in 1..pictures) {
            append("""<Relationship Id="rIdImg$n" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$n.jpeg"/>""")
        }
        append("</Relationships>")
    }

    private fun core(title: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${escape(title)}</dc:title><dc:creator>Oscanner</dc:creator></cp:coreProperties>"""

    /** XML text: escapes markup and drops characters XML 1.0 can't hold. */
    private fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> append(' ')
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val BODY_HALF_POINTS = 22
    /** The text column between 2 cm margins on an A4 page: 17 cm. */
    private const val TEXT_WIDTH_EMU = 6_120_000L
    /** The space between two columns: 1 cm (567 twips). */
    private const val COLUMN_SPACE_EMU = 360_000L

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style></w:styles>"""
}
