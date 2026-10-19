package dev.olaf.oscanner.core.imaging

import org.junit.Assert.assertEquals
import org.junit.Test

class BookSplitterTest {

    @Test
    fun findsShadowedGutter() {
        // Bright pages with a dark gutter at 55%.
        val profile = DoubleArray(200) { i -> if (i in 106..114) 120.0 else 230.0 }
        assertEquals(0.55, BookSplitter.valley(profile), 0.02)
    }

    @Test
    fun ignoresGutterOutsideTheMiddle() {
        // A dark page edge at 10% is not a spine.
        val profile = DoubleArray(200) { i -> if (i < 25) 60.0 else 230.0 }
        assertEquals(0.5, BookSplitter.valley(profile), 1e-9)
    }

    @Test
    fun flatSpreadSplitsInTheMiddle() {
        val profile = DoubleArray(200) { 228.0 + (it % 3) }
        assertEquals(0.5, BookSplitter.valley(profile), 1e-9)
    }
}
