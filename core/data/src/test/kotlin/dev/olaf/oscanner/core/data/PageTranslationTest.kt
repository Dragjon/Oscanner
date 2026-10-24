package dev.olaf.oscanner.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PageTranslationTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file() = File(folder.root, "translation.json")

    @Test
    fun aKeptTranslationComesBackForTheSameText() {
        val kept = PageTranslation("en", "fr", "Bonjour\nle monde", listOf("Bonjour", "le monde"))
        PageTranslation.write(file(), "Hello world", kept)
        assertEquals(kept, PageTranslation.read(file(), "Hello world"))
    }

    @Test
    fun fixedTextLeavesTheTranslationBehind() {
        PageTranslation.write(file(), "Helo world", PageTranslation("en", "fr", "Bonjour le monde"))
        assertNull(PageTranslation.read(file(), "Hello world"))
    }

    @Test
    fun aNewerTranslationReplacesTheOld() {
        PageTranslation.write(file(), "Hello world", PageTranslation("en", "fr", "Bonjour le monde"))
        PageTranslation.write(file(), "Hello world", PageTranslation("en", "de", "Hallo Welt"))
        assertEquals("Hallo Welt", PageTranslation.read(file(), "Hello world")?.text)
    }

    @Test
    fun noFileOrABrokenOneIsNothing() {
        assertNull(PageTranslation.read(file(), "Hello world"))
        file().writeText("{not json")
        assertNull(PageTranslation.read(file(), "Hello world"))
    }
}
