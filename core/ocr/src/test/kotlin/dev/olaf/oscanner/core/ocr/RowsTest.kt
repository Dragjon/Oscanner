package dev.olaf.oscanner.core.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class RowsTest {

    @Test
    fun aShortPieceAtALinesEndStaysOnItsLine() {
        // "Members met every second Tuesday in the library" (30 px tall), then "and" read on its
        // own a little lower and shorter, then the next line.
        val spans = listOf(100.0 to 130.0, 112.0 to 134.0, 150.0 to 180.0)
        assertEquals(listOf(listOf(0, 1), listOf(2)), PaddleReader.rows(spans))
    }

    @Test
    fun closeLinesStaySeparate() {
        val spans = listOf(100.0 to 130.0, 128.0 to 158.0, 156.0 to 186.0)
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), PaddleReader.rows(spans))
    }
}
