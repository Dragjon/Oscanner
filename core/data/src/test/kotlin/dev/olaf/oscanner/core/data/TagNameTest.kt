package dev.olaf.oscanner.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagNameTest {

    @Test
    fun collapsesSpaceAndDropsControlCharacters() {
        assertEquals("tax 2026", cleanTagName("  tax\u001F\n  2026 "))
    }

    @Test
    fun blankIsNoTag() {
        assertNull(cleanTagName(" \t "))
    }

    @Test
    fun longNamesAreCut() {
        assertEquals(40, cleanTagName("x".repeat(100))!!.length)
    }

    @Test
    fun searchQueryNeedsEveryWordAsPrefix() {
        assertEquals("elec* bill*", ftsQuery("elec \"bill\""))
        assertNull(ftsQuery("  -- "))
    }
}
