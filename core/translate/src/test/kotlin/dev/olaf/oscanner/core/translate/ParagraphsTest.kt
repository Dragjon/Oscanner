package dev.olaf.oscanner.core.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ParagraphsTest {

    @Test
    fun linesOfAParagraphJoinAndItsShortLastLineEndsIt() {
        val text = """
            Quarterly report
            Sales rose by twelve percent in the third quarter, led by strong
            demand for heat pumps in the north and a mild autumn that kept
            installers busy.
            Costs stayed flat.
        """.trimIndent()
        assertEquals(
            listOf(
                "Quarterly report",
                "Sales rose by twelve percent in the third quarter, led by strong demand for heat pumps in the north and a mild autumn that kept installers busy.",
                "Costs stayed flat.",
            ),
            Paragraphs.of(text),
        )
    }

    @Test
    fun blankLinesAndListItemsSeparate() {
        val text = "Please bring these things with you on the first day of the course:\n" +
            "- a pencil case\n- a calculator\n\nThe course starts at nine in the morning on Monday."
        assertEquals(
            listOf(
                "Please bring these things with you on the first day of the course:",
                "- a pencil case",
                "- a calculator",
                "The course starts at nine in the morning on Monday.",
            ),
            Paragraphs.of(text),
        )
    }

    @Test
    fun aSignOffAfterAFullLastLineStandsAlone() {
        val text = "Thank you for asking us to review the band of your home. We have looked\n" +
            "at similar homes and you can appeal within three months of this letter.\nYours sincerely\nJ Smith"
        assertEquals(
            listOf(
                "Thank you for asking us to review the band of your home. We have looked at similar homes and you can appeal within three months of this letter.",
                "Yours sincerely",
                "J Smith",
            ),
            Paragraphs.of(text),
        )
    }

    @Test
    fun aWordHyphenatedAcrossLinesIsMended() {
        val text = "The committee will publish its recommen-\ndations on the well-known website before the end\nof the month.\nThanks."
        assertEquals(
            listOf("The committee will publish its recommendations on the well-known website before the end of the month.", "Thanks."),
            Paragraphs.of(text),
        )
    }
}
