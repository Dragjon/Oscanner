package dev.olaf.oscanner.core.pdf

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal Excel workbook (Office Open XML), one sheet per page, written by hand like
 * [DocxWriter]: cells as typed text, numbers as numbers so they can be summed.
 */
object XlsxWriter {

    /** [sheets] are (name, rows of cells). */
    fun write(sheets: List<Pair<String, List<List<String>>>>, out: OutputStream) {
        require(sheets.isNotEmpty())
        ZipOutputStream(out).use { zip ->
            fun part(name: String, xml: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            part("[Content_Types].xml", contentTypes(sheets.size))
            part("_rels/.rels", ROOT_RELS)
            part("xl/workbook.xml", workbook(sheets.map { it.first }))
            part("xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
            sheets.forEachIndexed { i, (_, rows) -> part("xl/worksheets/sheet${i + 1}.xml", sheet(rows)) }
        }
    }

    /** A cell's text as a number, if it plainly is one: "1,250.00", "$42", "-3", "12%". */
    internal fun number(text: String): Double? {
        val t = text.trim().removePrefix("$").removePrefix("€").removePrefix("£").removeSuffix("%").replace(",", "")
        if (t.isEmpty() || t.count { it == '.' } > 1) return null
        if (!t.all { it.isDigit() || it == '.' || it == '-' } || t.lastIndexOf('-') > 0) return null
        if (text.trim().endsWith("%")) return null
        return t.toDoubleOrNull()
    }

    private fun sheet(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            append("""<row r="${r + 1}">""")
            cells.forEachIndexed { c, value ->
                if (value.isBlank()) return@forEachIndexed
                val ref = column(c) + (r + 1)
                val n = number(value)
                if (n != null) {
                    append("""<c r="$ref"><v>$n</v></c>""")
                } else {
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">""").append(escape(value)).append("</t></is></c>")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    internal fun column(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + i % 26)
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun workbook(names: List<String>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        names.forEachIndexed { i, name ->
            // Sheet names: at most 31 characters, none of : \ / ? * [ ].
            val safe = name.replace(Regex("""[:\\/?*\[\]]"""), " ").take(31).ifBlank { "Page ${i + 1}" }
            append("""<sheet name="${escape(safe)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
        }
        append("</sheets></workbook>")
    }

    private fun workbookRels(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun contentTypes(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        for (i in 1..count) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        append("</Types>")
    }

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private fun escape(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                // XML 1.0 has no place for most control characters.
                else -> if (ch >= ' ' || ch == '\t' || ch == '\n') append(ch)
            }
        }
    }
}
