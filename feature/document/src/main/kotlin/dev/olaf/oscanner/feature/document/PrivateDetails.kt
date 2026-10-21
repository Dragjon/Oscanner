package dev.olaf.oscanner.feature.document

import dev.olaf.oscanner.core.model.WordBox
import kotlin.math.max
import kotlin.math.min

/** A rectangle on the page as shown, normalized like [WordBox]. */
data class PageRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Finds things people usually black out before sharing, in the words read from a page. It only
 * suggests: everything found is shown covered, and any box can be tapped away.
 */
object PrivateDetails {

    private val email = Regex("""^[^\s@]+@[^\s@]+\.\p{L}{2,}$""")
    /** Digits with the separators numbers are printed with: 4111-1111, (555), +44, 12.34. */
    private val numberish = Regex("""^[+(]?\d[\d\-./)]*$""")
    /** The country code and check digits that open an IBAN, like DE89. */
    private val ibanStart = Regex("""^[A-Z]{2}\d{2}$""")
    private val date = Regex("""^\d{1,4}[./-]\d{1,2}[./-]\d{1,4}$""")

    fun emails(words: List<WordBox>): List<List<WordBox>> =
        words.filter { email.matches(trimPunctuation(it.text)) }.map { listOf(it) }

    /**
     * Card, account, phone and ID numbers: runs of number-like words on one line holding at
     * least [MIN_DIGITS] digits. A lone date isn't one, so a letter's date stays readable.
     */
    fun numbers(words: List<WordBox>): List<List<WordBox>> {
        val groups = mutableListOf<List<WordBox>>()
        var run = mutableListOf<WordBox>()
        fun flush() {
            val digits = run.sumOf { w -> w.text.count(Char::isDigit) }
            val lone = run.singleOrNull()?.let { date.matches(trimPunctuation(it.text)) } == true
            if (digits >= MIN_DIGITS && !lone) groups += run
            run = mutableListOf()
        }
        for (word in words) {
            val text = trimPunctuation(word.text)
            val fits = numberish.matches(text) || (run.isEmpty() && ibanStart.matches(text))
            if (!fits) {
                flush()
                continue
            }
            if (run.isNotEmpty() && !sameLine(run.last(), word)) flush()
            run += word
        }
        flush()
        return groups
    }

    /**
     * All the text, a line at a time: words in reading order on one line, split where a gap is
     * wider than [COLUMN_GAP] letter heights (a second column, a form's label and its value), so
     * a box covers words and not the space between columns.
     */
    fun lines(words: List<WordBox>): List<List<WordBox>> {
        val groups = mutableListOf<MutableList<WordBox>>()
        for (word in words) {
            val run = groups.lastOrNull()
            val last = run?.last()
            val joins = last != null && sameLine(last, word) &&
                word.left - last.right < COLUMN_GAP * min(last.bottom - last.top, word.bottom - word.top) &&
                word.left > last.left
            if (joins) run!! += word else groups += mutableListOf(word)
        }
        return groups
    }

    /** Every place the words of [query] appear in a row, ignoring case and punctuation. */
    fun matching(words: List<WordBox>, query: String): List<List<WordBox>> {
        val wanted = query.split(Regex("""\s+""")).map(::normalize).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        val found = mutableListOf<List<WordBox>>()
        var i = 0
        while (i + wanted.size <= words.size) {
            val window = words.subList(i, i + wanted.size)
            if (window.map { normalize(it.text) } == wanted) {
                found += window.toList()
                i += wanted.size
            } else {
                i++
            }
        }
        return found
    }

    /**
     * One rectangle per line of a group, padded so letter edges and accents are covered too.
     * [pageAspect] is height ÷ width, to pad sideways by the same distance as up and down.
     */
    fun rects(group: List<WordBox>, pageAspect: Float): List<PageRect> {
        val lines = mutableListOf<MutableList<WordBox>>()
        for (word in group) {
            val line = lines.lastOrNull()
            if (line != null && sameLine(line.last(), word)) line += word else lines += mutableListOf(word)
        }
        return lines.map { line ->
            val top = line.minOf { it.top }
            val bottom = line.maxOf { it.bottom }
            val padY = (bottom - top) * PAD
            val padX = padY * pageAspect
            PageRect(
                (line.minOf { it.left } - padX).coerceAtLeast(0f),
                (top - padY).coerceAtLeast(0f),
                (line.maxOf { it.right } + padX).coerceAtMost(1f),
                (bottom + padY).coerceAtMost(1f),
            )
        }
    }

    /** Overlapping by half the shorter word's height counts as one line. */
    private fun sameLine(a: WordBox, b: WordBox): Boolean {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap > 0.5f * min(a.bottom - a.top, b.bottom - b.top)
    }

    private fun trimPunctuation(text: String) = text.trim().trimEnd(',', ';', ':', '.').trimStart('<', '[').trimEnd('>', ']')

    private fun normalize(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private const val MIN_DIGITS = 7
    private const val COLUMN_GAP = 3f
    private const val PAD = 0.25f
}
