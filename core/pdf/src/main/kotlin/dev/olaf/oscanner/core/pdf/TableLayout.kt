package dev.olaf.oscanner.core.pdf

import kotlin.math.max
import kotlin.math.min

/**
 * The words read from a page, arranged as a table: rows from words that sit on one line,
 * columns from the gaps that run down the page between them. A page of plain text comes out as
 * one column of lines; a receipt or a form as its columns.
 */
object TableLayout {

    /** Rows of cells, each cell the words that fell in it, joined by spaces. */
    fun cells(words: List<PdfWord>): List<List<String>> {
        val clean = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (clean.isEmpty()) return emptyList()
        val rows = rows(clean)
        val height = clean.map { it.bottom - it.top }.sorted()[clean.size / 2]
        // Within a row, words closer than about a letter's width are one phrase ("Total due").
        val phrases = rows.map { row -> phrases(row, height * PHRASE_GAP) }
        val columns = columns(phrases, height)
        return phrases.map { row ->
            val cells = MutableList(columns.size) { "" }
            for (p in row) {
                val middle = (p.left + p.right) / 2
                val col = columns.indexOfFirst { middle >= it.first && middle <= it.second }.takeIf { it >= 0 }
                    ?: columns.indices.minBy { i -> distance(middle, columns[i]) }
                cells[col] = if (cells[col].isEmpty()) p.text else cells[col] + " " + p.text
            }
            cells
        }
    }

    private data class Phrase(val text: String, val left: Float, val right: Float)

    /** Words whose middles share a line, top to bottom, each row left to right. */
    private fun rows(words: List<PdfWord>): List<List<PdfWord>> {
        val rows = mutableListOf<MutableList<PdfWord>>()
        for (w in words.sortedBy { (it.top + it.bottom) / 2 }) {
            val row = rows.lastOrNull()
            val middle = (w.top + w.bottom) / 2
            if (row != null) {
                val top = row.minOf { it.top }
                val bottom = row.maxOf { it.bottom }
                val overlap = min(bottom, w.bottom) - max(top, w.top)
                if (middle in top..bottom || overlap > 0.5f * min(bottom - top, w.bottom - w.top)) {
                    row += w
                    continue
                }
            }
            rows += mutableListOf(w)
        }
        return rows.map { r -> r.sortedBy { it.left } }
    }

    private fun phrases(row: List<PdfWord>, gap: Float): List<Phrase> {
        val out = mutableListOf<Phrase>()
        var text = StringBuilder(row.first().text)
        var left = row.first().left
        var right = row.first().right
        for (w in row.drop(1)) {
            if (w.left - right <= gap) {
                text.append(' ').append(w.text)
                right = max(right, w.right)
            } else {
                out += Phrase(text.toString(), left, right)
                text = StringBuilder(w.text)
                left = w.left
                right = w.right
            }
        }
        out += Phrase(text.toString(), left, right)
        return out
    }

    /**
     * Column spans: x positions no phrase covers split the page into columns. Rows with a single
     * wide phrase (a heading, a sentence) would bridge every gap, so they don't take part.
     */
    private fun columns(rows: List<List<Phrase>>, height: Float): List<Pair<Float, Float>> {
        val spans = rows.filter { it.size > 1 }.flatten().map { it.left to it.right }
        if (spans.isEmpty()) return listOf(Float.NEGATIVE_INFINITY to Float.POSITIVE_INFINITY)
        val merged = mutableListOf<Pair<Float, Float>>()
        for ((l, r) in spans.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            // A sliver of a gap (smaller than a letter) is ragged text, not a column break.
            if (last != null && l <= last.second + height * COLUMN_GAP) {
                merged[merged.lastIndex] = last.first to max(last.second, r)
            } else {
                merged += l to r
            }
        }
        return merged
    }

    private fun distance(x: Float, span: Pair<Float, Float>) = when {
        x < span.first -> span.first - x
        x > span.second -> x - span.second
        else -> 0f
    }

    /** Words closer than this many line heights are one phrase. */
    private const val PHRASE_GAP = 0.9f
    /** Column gaps narrower than this many line heights are closed. */
    private const val COLUMN_GAP = 0.5f
}
