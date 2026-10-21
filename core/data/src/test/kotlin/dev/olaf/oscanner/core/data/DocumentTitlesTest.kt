package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentTitlesTest {

    private fun line(text: String, left: Float, top: Float, h: Float = 0.015f, w: Float = 0.07f): List<PdfWord> =
        text.split(' ').mapIndexed { i, word -> PdfWord(word, left + i * (w + 0.01f), top, left + i * (w + 0.01f) + w, top + h) }

    private fun body(top: Float) = (0 until 6).flatMap { line("the club planted forty new trees along the river", 0.1f, top + it * 0.025f) }

    @Test
    fun namesADocumentAfterItsHeading() {
        val words = line("Garden Club Report", 0.3f, 0.06f, h = 0.035f, w = 0.12f) + body(0.15f)
        assertEquals("Garden Club Report", DocumentTitles.fromWords(words))
    }

    @Test
    fun takesAShortFirstLineWhenThereIsNoHeading() {
        val words = line("Dear Sam,", 0.1f, 0.08f) + body(0.12f)
        assertEquals("Dear Sam", DocumentTitles.fromWords(words))
    }

    @Test
    fun leavesAPageOfPlainTextOrNumbersAlone() {
        assertNull(DocumentTitles.fromWords(body(0.1f)))
        assertNull(DocumentTitles.fromWords(line("12/03/2025", 0.1f, 0.05f) + body(0.12f)))
        assertNull(DocumentTitles.fromWords(emptyList()))
    }

    @Test
    fun cutsALongHeadingAtAWord() {
        val long = "Annual report of the allotment society for the year two thousand and twenty six"
        assertEquals("Annual report of the allotment society for the year two…", DocumentTitles.tidy(long))
    }
}
