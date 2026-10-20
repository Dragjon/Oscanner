package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintedLinesTest {

    @Test
    fun wordsOnOneLineMakeOneBand() {
        val words = listOf(Area(0.1f, 0.10f, 0.2f, 0.12f), Area(0.25f, 0.101f, 0.4f, 0.121f))
        val lines = Cleanup.printedLines(words, 1000, 1000)
        assertEquals(1, lines.size)
        assertTrue(lines[0][0] < 100.0 && lines[0][2] > 400.0)
    }

    @Test
    fun aLineEndingInUnreadWordsReachesItsNeighborsMargin() {
        // The middle line's last words read poorly, so only its first word is known.
        val words = listOf(
            Area(0.1f, 0.10f, 0.7f, 0.12f),
            Area(0.1f, 0.13f, 0.2f, 0.15f),
            Area(0.1f, 0.16f, 0.7f, 0.18f),
        )
        val middle = Cleanup.printedLines(words, 1000, 1000).single { it[1] in 120.0..130.0 }
        assertTrue(middle[2] >= 700.0)
    }

    @Test
    fun separateParagraphsKeepTheirOwnMargins() {
        val words = listOf(Area(0.1f, 0.10f, 0.3f, 0.12f), Area(0.1f, 0.60f, 0.9f, 0.62f))
        val top = Cleanup.printedLines(words, 1000, 1000).single { it[1] < 200.0 }
        assertTrue(top[2] < 400.0)
    }
}
