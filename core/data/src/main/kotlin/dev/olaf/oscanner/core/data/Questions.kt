package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the numbered questions of a test or worksheet in the words read from its pages: lines
 * that open, at the left of the text, with "1.", "2)", "Q3", "Question 4" and so on, numbered in
 * sequence. Only the longest run counts, so a list numbered inside a question, a page number or
 * a stray "5" in the margin doesn't start a question.
 */
object Questions {

    /** Where a question starts: which page, and the top of its first line (page coordinates). */
    data class Start(val page: Int, val number: Int, val top: Float, val lineHeight: Float)

    /** The questions' starts across [pages] (each its read words), in reading order; empty under two. */
    fun starts(pages: List<List<PdfWord>>): List<Start> {
        val candidates = pages.flatMapIndexed { index, words -> candidates(index, words) }
        // The longest chain of numbers going up by one, read in order.
        val best = IntArray(candidates.size) { 1 }
        val previous = IntArray(candidates.size) { -1 }
        for (i in candidates.indices) {
            for (j in 0 until i) {
                if (candidates[j].number == candidates[i].number - 1 && best[j] + 1 > best[i]) {
                    best[i] = best[j] + 1
                    previous[i] = j
                }
            }
        }
        val end = best.indices.maxByOrNull { best[it] } ?: return emptyList()
        if (best[end] < MIN_QUESTIONS) return emptyList()
        return generateSequence(end) { previous[it].takeIf { p -> p >= 0 } }.map { candidates[it] }.toList().reversed()
    }

    /** Lines of [words] that open with a question number, top to bottom. */
    private fun candidates(page: Int, words: List<PdfWord>): List<Start> {
        if (words.isEmpty()) return emptyList()
        val left = words.minOf { it.left }
        val right = words.maxOf { it.right }
        return lines(words).mapNotNull { line ->
            val first = line.first()
            // At the margin, not in the middle of a line or far indented.
            if (first.left - left > MARGIN * (right - left)) return@mapNotNull null
            val number = number(line) ?: return@mapNotNull null
            Start(page, number, line.minOf { it.top }, line.map { it.bottom - it.top }.average().toFloat())
        }
    }

    /** The question number a line opens with, if it does. */
    private fun number(line: List<PdfWord>): Int? {
        val first = line[0].text.trim()
        numbered.matchEntire(first)?.let { m ->
            val n = m.groupValues[2].toInt()
            // A bare "3" needs words after it, and a mark: "Q3", "3.", "3)" stand alone.
            val marked = m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty()
            return n.takeIf { (marked || line.size > 1) && it in 1..MAX_NUMBER }
        }
        // "Q 3" or "Question 3." as two words.
        if (line.size > 1 && word.matches(first)) {
            return numbered.matchEntire(line[1].text.trim())?.takeIf { it.groupValues[1].isEmpty() }
                ?.groupValues?.get(2)?.toInt()?.takeIf { it in 1..MAX_NUMBER }
        }
        return null
    }

    /** Words sharing a line, top to bottom, each line left to right. */
    private fun lines(words: List<PdfWord>): List<List<PdfWord>> {
        val rows = mutableListOf<MutableList<PdfWord>>()
        for (w in words.sortedBy { (it.top + it.bottom) / 2 }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val top = row.minOf { it.top }
                val bottom = row.maxOf { it.bottom }
                if (min(bottom, w.bottom) - max(top, w.top) > 0.5f * min(bottom - top, w.bottom - w.top)) {
                    row += w
                    continue
                }
            }
            rows += mutableListOf(w)
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    /** An optional "Q" or "Question", the number, an optional "." ")" or ":". */
    private val numbered = Regex("""^((?:Q|Question)\.?\s*)?\(?(\d{1,2})([.):])?$""", RegexOption.IGNORE_CASE)
    private val word = Regex("""^(Q|Question)\.?$""", RegexOption.IGNORE_CASE)

    private const val MIN_QUESTIONS = 2
    private const val MAX_NUMBER = 60
    /** A question's number starts within this share of the text's width from its left edge. */
    private const val MARGIN = 0.12f
}
