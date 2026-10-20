package dev.olaf.oscanner.core.ocr

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class WordFixerTest {

    private fun sure(word: String) = word.map { LetterGuess(listOf(it to 0.99f)) }

    @Test
    fun aDoubtfulLetterTakesTheRunnerUpThatMakesAWord() {
        // "deodline", the reader unsure of the o.
        val word = sure("de") + LetterGuess(listOf('o' to 0.6f, 'a' to 0.3f)) + sure("dline")
        assertEquals(listOf("the", "deadline"), fixer.fix(listOf(sure("the"), word)))
    }

    @Test
    fun theNeighborsDecideBetweenRealWords() {
        val day = listOf(LetterGuess(listOf('d' to 0.99f)), LetterGuess(listOf('o' to 0.55f, 'a' to 0.4f)), LetterGuess(listOf('y' to 0.99f)), LetterGuess(listOf('s' to 0.99f)))
        assertEquals("days", fixer.fix(listOf(sure("three"), day))[1])
    }

    @Test
    fun wordsReadWithConfidenceStayAsRead() {
        assertEquals(listOf("call", "Bam"), fixer.fix(listOf(sure("call"), sure("Bam"))))
    }

    @Test
    fun aRealWordOnlyGivesWayToAClearlyLikelierOne() {
        val must = listOf(LetterGuess(listOf('m' to 0.99f)), LetterGuess(listOf('u' to 0.7f, 'o' to 0.25f)), LetterGuess(listOf('s' to 0.99f)), LetterGuess(listOf('t' to 0.99f)))
        assertEquals("must", fixer.fix(listOf(sure("students"), must))[1])
    }

    @Test
    fun punctuationAroundAWordIsKept() {
        val word = sure("\"") + listOf(LetterGuess(listOf('w' to 0.99f)), LetterGuess(listOf('o' to 0.5f, 'a' to 0.45f)), LetterGuess(listOf('t' to 0.99f)), LetterGuess(listOf('e' to 0.99f)), LetterGuess(listOf('r' to 0.99f))) + sure(",")
        assertEquals("\"water,", fixer.fix(listOf(sure("of"), word))[1])
    }

    @Test
    fun codesAreLeftAlone() {
        val code = sure("AB") + listOf(LetterGuess(listOf('C' to 0.5f, 'G' to 0.4f)))
        assertEquals("ABC", fixer.fix(listOf(code))[0])
    }

    companion object {
        private lateinit var fixer: WordFixer

        @BeforeClass
        @JvmStatic
        fun load() {
            val dir = File("src/main/assets/paddle")
            fixer = WordFixer.load(File(dir, "words.dat").inputStream(), File(dir, "pairs.dat").inputStream())
        }
    }
}
