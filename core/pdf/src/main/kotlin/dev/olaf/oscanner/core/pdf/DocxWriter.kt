package dev.olaf.oscanner.core.pdf

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A Word document (.docx, Office Open XML) of recognized text: the title, then each page's
 * paragraphs, with a page break between scanned pages. Written by hand like the PDF, so there's
 * no dependency: a .docx is a zip of a few XML parts.
 */
object DocxWriter {

    fun write(title: String, pages: List<String>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", CONTENT_TYPES)
            part("_rels/.rels", ROOT_RELS)
            part("word/_rels/document.xml.rels", DOCUMENT_RELS)
            part("word/styles.xml", STYLES)
            part("docProps/core.xml", core(title))
            part("word/document.xml", document(title, pages))
        }
    }

    /**
     * Turns OCR text into paragraphs: blank lines separate them; lines inside one are joined
     * with spaces, and a word broken across lines with a hyphen is put back together.
     */
    internal fun paragraphs(text: String): List<String> =
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

    private fun document(title: String, pages: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<w:document xmlns:w="$W"><w:body>""")
        append("""<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t xml:space="preserve">${escape(title)}</w:t></w:r></w:p>""")
        pages.forEachIndexed { index, page ->
            if (index > 0) append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
            paragraphs(page).forEach { append("""<w:p><w:r><w:t xml:space="preserve">${escape(it)}</w:t></w:r></w:p>""") }
        }
        // Letter/A4-neutral margins of 2 cm; the page size is left to the reader's default.
        append("""<w:sectPr><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="567" w:footer="567" w:gutter="0"/></w:sectPr>""")
        append("</w:body></w:document>")
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

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="40"/></w:rPr></w:style></w:styles>"""
}
