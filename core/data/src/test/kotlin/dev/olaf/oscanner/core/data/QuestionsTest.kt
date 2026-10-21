package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionsTest {

    /** A page: each entry is a line of words at (left, top), letters 0.02 tall. */
    private fun page(vararg lines: Pair<Float, String>): List<PdfWord> = lines.flatMapIndexed { row, (left, text) ->
        var x = left
        text.split(" ").map { w ->
            val width = 0.015f * w.length
            PdfWord(w, x, 0.1f + row * 0.04f, x + width, 0.12f + row * 0.04f).also { x += width + 0.01f }
        }
    }

    @Test
    fun findsNumberedQuestionsAndSkipsListsInsideThem() {
        val sheet = page(
            0.1f to "Answer all questions. Time: 1 hour",
            0.1f to "1. Name three planets",
            0.1f to "2) What is 7 x 8 ?",
            0.15f to "1. a list item inside question two",
            0.15f to "2. and another",
            0.1f to "Q3 Explain why the sky is blue",
            0.1f to "the answer goes here 5",
            0.1f to "Question 4. Draw a map",
        )
        val starts = Questions.starts(listOf(sheet))
        assertEquals(listOf(1, 2, 3, 4), starts.map { it.number })
        assertEquals(0.1f + 1 * 0.04f, starts[0].top, 1e-6f)
        assertEquals(0.1f + 7 * 0.04f, starts[3].top, 1e-6f)
    }

    @Test
    fun followsQuestionsOntoTheNextPage() {
        val one = page(0.1f to "1. First", 0.1f to "2. Second")
        val two = page(0.1f to "continued from two", 0.1f to "3. Third")
        val starts = Questions.starts(listOf(one, two))
        assertEquals(listOf(0 to 1, 0 to 2, 1 to 3), starts.map { it.page to it.number })
    }

    @Test
    fun aPageWithoutQuestionsHasNone() {
        assertEquals(emptyList<Questions.Start>(), Questions.starts(listOf(page(0.1f to "Dear Sam", 0.1f to "Thanks for the 3 books"))))
        // One numbered line isn't a test.
        assertEquals(emptyList<Questions.Start>(), Questions.starts(listOf(page(0.1f to "1. Only one"))))
    }
}
