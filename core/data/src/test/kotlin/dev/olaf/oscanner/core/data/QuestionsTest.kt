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

    @Test
    fun aNumberAloneOverADiagramStartsAQuestion() {
        val one = page(0.1f to "1 Solve the equation", 0.1f to "2")
        val two = page(0.1f to "3. Find x", 0.1f to "4 (a) Sketch the graph")
        assertEquals(listOf(1, 2, 3, 4), Questions.starts(listOf(one, two)).map { it.number })
    }

    @Test
    fun aMissedNumberDoesNotBreakTheRun() {
        val sheet = page(0.1f to "1 First", 0.1f to "2 Second", 0.1f to "the third lost its number", 0.1f to "4 Fourth", 0.1f to "5 Fifth")
        assertEquals(listOf(1, 2, 4, 5), Questions.starts(listOf(sheet)).map { it.number })
    }

    @Test
    fun partsRunOnGluedAndLetteredNumbersCount() {
        val sheet = page(0.1f to "1(a) Solve", 0.1f to "2.(a)(i) Shade", 0.1f to "3a) Name", 0.1f to "3b) Explain", 0.1f to "4 Last")
        assertEquals(listOf(1, 2, 3, 4), Questions.starts(listOf(sheet)).map { it.number })
    }

    @Test
    fun aTableOfMarksAndLinesToAnswerOnAreNotQuestions() {
        val cover = page(0.1f to "Question 1 2 3 4", 0.1f to "Question 5 6 7 8", 0.1f to "Marks")
        val sheet = page(0.1f to "1 Name two gases", 0.1f to "1 ..........", 0.1f to "2 ..........", 0.1f to "2 Why ?", 0.1f to "3 How ?")
        val starts = Questions.starts(listOf(cover, sheet))
        assertEquals(listOf(1 to 1, 1 to 2, 1 to 3), starts.map { it.page to it.number })
        assertEquals(0.1f, starts[0].top, 1e-6f)
    }

    @Test
    fun partsLeaveOutRunningHeadsFeetAndBlankPagesAndFollowTheLastQuestion() {
        fun at(top: Float, text: String) = PdfWord(text, 0.1f, top, 0.1f + 0.015f * text.length, top + 0.02f)
        fun sheet(n: Int, vararg body: PdfWord) = listOf(PdfWord("$n", 0.5f, 0.02f, 0.52f, 0.04f)) + body + PdfWord("PRELIM/2025", 0.4f, 0.95f, 0.6f, 0.97f)
        val pages = listOf(
            sheet(1, at(0.2f, "1"), PdfWord("Solve", 0.15f, 0.2f, 0.25f, 0.22f), at(0.6f, "2"), PdfWord("Find", 0.15f, 0.6f, 0.25f, 0.62f)),
            sheet(2, at(0.15f, "(b) Hence")),
            sheet(3, PdfWord("BLANK PAGE", 0.4f, 0.5f, 0.6f, 0.52f)),
            sheet(4, at(0.1f, "3"), PdfWord("Prove", 0.15f, 0.1f, 0.25f, 0.12f)),
            sheet(5, at(0.1f, "(c) Show"), at(0.5f, "End of Paper")),
        )
        val parts = Questions.parts(pages)
        assertEquals(listOf(1 to 0, 2 to 0, 2 to 1, 3 to 3, 3 to 4), parts.map { it.number to it.page })
        // Down to the foot, not over it; a page over starts under its running head.
        assertEquals(0.6f - 0.016f, parts[0].bottom, 1e-4f)
        assertEquals(0.95f - 0.016f, parts[1].bottom, 1e-4f)
        assertEquals(0.15f - 0.016f, parts[2].top, 1e-4f)
        // The last question stops at "End of Paper".
        assertEquals(0.12f + 0.016f, parts[4].bottom, 1e-4f)
    }

    @Test
    fun aStrayRepeatOfANumberDoesNotHideTheQuestion() {
        // An answer line "2. ...... [2]" in question 1, then question 2 itself.
        val sheet = page(0.1f to "1 Name the organ", 0.1f to "2. .......... [2]", 0.1f to "2 Fig. 2.1 shows", 0.1f to "3 Explain")
        val starts = Questions.starts(listOf(sheet))
        assertEquals(listOf(1, 2, 3), starts.map { it.number })
        assertEquals(0.1f + 2 * 0.04f, starts[1].top, 1e-6f)
    }

    @Test
    fun questionsNotFoundBeforeTheFirstComeAlongAfterTheCover() {
        val cover = page(0.1f to "Answer all questions.", 0.1f to "Write your name and index number")
        val passage = page(0.1f to "Fill in the blanks: the sky is 1 ( blue / red ) and", 0.1f to "the grass is 2 ( green / grey ) today.")
        val questions = page(0.1f to "3 Why is the sky blue ?", 0.1f to "4 Why is grass green ?")
        val parts = Questions.parts(listOf(cover, passage, questions))
        assertEquals(listOf(0 to 1, 3 to 2, 4 to 2), parts.map { it.number to it.page })
    }

    @Test
    fun aPassageUnderAHeadingGoesWithTheQuestionsAfterIt() {
        val sheet = page(
            0.1f to "1 What is a dialect ?",
            0.1f to "2 Who speaks it ?",
            0.1f to "Read the following passage and answer questions 3 to 4",
            0.1f to "Long ago in a village by the sea",
            0.1f to "there lived an old fisherman.",
            0.1f to "3 Where did he live ?",
            0.1f to "4 What was his work ?",
        )
        val parts = Questions.parts(listOf(sheet))
        assertEquals(listOf(1, 2, 3, 4), parts.map { it.number })
        // Question 2 stops above the heading; question 3 starts there, with the passage.
        val heading = 0.1f + 2 * 0.04f - 0.016f
        assertEquals(heading, parts[1].bottom, 1e-4f)
        assertEquals(heading, parts[2].top, 1e-4f)
    }
}
