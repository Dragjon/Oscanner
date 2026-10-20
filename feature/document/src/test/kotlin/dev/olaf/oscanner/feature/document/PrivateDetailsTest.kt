package dev.olaf.oscanner.feature.document

import dev.olaf.oscanner.core.model.WordBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateDetailsTest {

    /** Words laid out left to right; each [lines] entry is one line of text. */
    private fun page(vararg lines: String): List<WordBox> = lines.flatMapIndexed { row, line ->
        var x = 0.05f
        line.split(" ").map { text ->
            val w = 0.02f * text.length
            WordBox(text, x, 0.1f + row * 0.05f, x + w, 0.13f + row * 0.05f).also { x += w + 0.02f }
        }
    }

    @Test
    fun findsEmailsWithTrailingPunctuation() {
        val words = page("Write to ana.lima@example.org, or call")
        assertEquals(listOf("ana.lima@example.org,"), PrivateDetails.emails(words).map { it.single().text })
    }

    @Test
    fun groupsACardNumberPrintedInFours() {
        val words = page("Card 4111 1111 1111 1111 expires 09/29")
        val found = PrivateDetails.numbers(words)
        assertEquals(1, found.size)
        assertEquals(listOf("4111", "1111", "1111", "1111"), found[0].map { it.text })
    }

    @Test
    fun findsPhoneNumbersAndIbans() {
        val words = page("Phone +44 (20) 7946-0958", "IBAN DE89 3704 0044 0532 0130 00")
        val found = PrivateDetails.numbers(words).map { g -> g.joinToString(" ") { it.text } }
        assertEquals(listOf("+44 (20) 7946-0958", "DE89 3704 0044 0532 0130 00"), found)
    }

    @Test
    fun leavesDatesAndShortNumbersAlone() {
        val words = page("Dated 12/03/2026 page 2 of 14 total 1234")
        assertTrue(PrivateDetails.numbers(words).isEmpty())
    }

    @Test
    fun numbersDontRunAcrossLines() {
        val words = page("Account 1234", "5678 90")
        assertTrue(PrivateDetails.numbers(words).isEmpty())
    }

    @Test
    fun matchesNamesIgnoringCaseAndPunctuation() {
        val words = page("Dear Ana Lima, thank you.", "Signed: ANA LIMA")
        val found = PrivateDetails.matching(words, "ana lima")
        assertEquals(2, found.size)
        assertEquals(listOf("Ana", "Lima,"), found[0].map { it.text })
    }

    @Test
    fun rectsArePaddedAndOnePerLine() {
        val words = page("Ana", "Lima")
        val rects = PrivateDetails.rects(words, pageAspect = 1f)
        assertEquals(2, rects.size)
        assertTrue(rects[0].top < words[0].top && rects[0].bottom > words[0].bottom)
        assertTrue(rects[0].left < words[0].left)
    }
}
