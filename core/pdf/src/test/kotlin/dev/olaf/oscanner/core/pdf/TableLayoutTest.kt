package dev.olaf.oscanner.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class TableLayoutTest {

    /** A word at column x (in letters), line y, as a page of 100 letters by 60 lines would place it. */
    private fun word(text: String, x: Int, y: Int) =
        PdfWord(text, x / 100f, y / 60f, (x + text.length) / 100f, (y + 0.8f) / 60f)

    @Test
    fun receiptBecomesColumns() {
        val words = listOf(
            word("Coffee", 5, 10), word("2", 40, 10), word("7.00", 60, 10),
            word("Bagel", 5, 11), word("1", 40, 11), word("3.50", 60, 11),
            word("Orange", 5, 12), word("juice", 12, 12), word("1", 40, 12), word("4.25", 60, 12),
        )
        assertEquals(
            listOf(
                listOf("Coffee", "2", "7.00"),
                listOf("Bagel", "1", "3.50"),
                listOf("Orange juice", "1", "4.25"),
            ),
            TableLayout.cells(words),
        )
    }

    @Test
    fun headingDoesNotMergeColumns() {
        val words = listOf(
            word("Monthly", 5, 2), word("expenses", 13, 2), word("for", 22, 2), word("October", 26, 2), word("2026", 34, 2),
            word("Rent", 5, 4), word("1200", 60, 4),
            word("Food", 5, 5), word("350", 60, 5),
        )
        val cells = TableLayout.cells(words)
        assertEquals(listOf("Monthly expenses for October 2026", ""), cells[0])
        assertEquals(listOf("Rent", "1200"), cells[1])
    }

    @Test
    fun plainTextIsOneColumn() {
        val words = listOf(word("Dear", 5, 1), word("Sam,", 10, 1), word("Thank", 5, 3), word("you.", 11, 3))
        assertEquals(listOf(listOf("Dear Sam,"), listOf("Thank you.")), TableLayout.cells(words))
    }

    @Test
    fun numbersAreNumbers() {
        assertEquals(1250.0, XlsxWriter.number("1,250.00")!!, 1e-9)
        assertEquals(42.0, XlsxWriter.number("$42")!!, 1e-9)
        assertEquals(-3.0, XlsxWriter.number("-3")!!, 1e-9)
        assertNull(XlsxWriter.number("12%"))
        assertNull(XlsxWriter.number("2026-10-21"))
        assertNull(XlsxWriter.number("Total"))
        assertEquals("A", XlsxWriter.column(0))
        assertEquals("Z", XlsxWriter.column(25))
        assertEquals("AA", XlsxWriter.column(26))
    }

    @Test
    fun workbookHasASheetPerPage() {
        val out = ByteArrayOutputStream()
        XlsxWriter.write(listOf("Page 1" to listOf(listOf("a", "1")), "Page 2" to listOf(listOf("b & c"))), out)
        val names = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                names[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml"), names.keys)
        assert(names["xl/worksheets/sheet1.xml"]!!.contains("<v>1.0</v>"))
        assert(names["xl/worksheets/sheet2.xml"]!!.contains("b &amp; c"))
    }
}
