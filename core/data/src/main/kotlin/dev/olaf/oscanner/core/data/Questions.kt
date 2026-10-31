package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the numbered questions of a test or worksheet in the words read from its pages: lines
 * that open, at the left of the text, with "1.", "2)", "3(a)", "Q4", "Question 5" and so on,
 * numbered in sequence. Only the longest run counts, so a list numbered inside a question, a page
 * number or a stray "5" in the margin doesn't start a question; a number or two the reader
 * missed doesn't break the run.
 */
object Questions {

    /** Where a question starts: which page, the top of its first line and that line's left (page coordinates). */
    data class Start(val page: Int, val number: Int, val top: Float, val lineHeight: Float, val left: Float = 0f)

    /** A stretch of a page belonging to question [number], from [top] to [bottom] down it (page coordinates). */
    data class Part(val page: Int, val number: Int, val top: Float, val bottom: Float)

    /** The questions' starts across [pages] (each its read words), in reading order; empty under two. */
    fun starts(pages: List<List<PdfWord>>): List<Start> {
        val candidates = mutableListOf<Start>()
        for ((start, part) in pages.flatMapIndexed { index, words -> candidates(index, words) }) {
            // "9a)" then "9b)": one question in parts, starting at the first.
            if (part && candidates.lastOrNull()?.number == start.number) continue
            candidates += start
        }
        val first = run(candidates)
        if (first.isEmpty()) return emptyList()
        // Question numbers stand in one column: what's found away from it (a number opening a
        // line of a question's text, a scale on a graph) is dropped, and the run found again.
        val column = first.map { it.left }.sorted()[first.size / 2]
        return run(candidates.filter { abs(it.left - column) < COLUMN })
    }

    /**
     * Each question of [pages] cut into the stretches of pages it covers, in order: from its
     * number down to the next question's, over the page if need be. A page's running head and
     * foot and its number are left out, and so are blank pages; the last question runs on over
     * the page until the paper ends, but no further than a few pages. When the first question
     * found isn't number 1 (the first ones are blanks in a passage, say), what comes before it,
     * after the cover, is a part of its own, numbered 0. A heading between two questions goes
     * with the second, and so does the passage under it. Empty under two questions.
     */
    fun parts(pages: List<List<PdfWord>>): List<Part> {
        val starts = starts(pages)
        if (starts.isEmpty()) return emptyList()
        val lines = pages.map { lines(it) }
        val furniture = furniture(lines)
        val body = lines.mapIndexed { p, page -> page.filterNot { it in furniture[p] } }
        fun empty(p: Int) = body[p].isEmpty() || blankPage.containsMatchIn(body[p].joinToString(" ") { text(it) })
        fun textTop(p: Int) = body[p].minOf { line -> line.minOf { it.top } }

        /**
         * Where a stretch of page [p] below [below] ends, [pad] under its text: at the page's foot,
         * with the room left to answer in, or at "End of paper" (true: nothing follows).
         */
        fun foot(p: Int, below: Float, pad: Float): Pair<Float, Boolean> {
            val end = body[p].filter { line -> line.minOf { it.top } > below && endOfPaper.containsMatchIn(text(line)) }
                .minOfOrNull { line -> line.minOf { it.top } }
            if (end != null) return (body[p].flatten().filter { it.bottom <= end }.maxOfOrNull { it.bottom } ?: below) + pad to true
            val text = body[p].maxOf { line -> line.maxOf { it.bottom } }
            val foot = furniture[p].map { line -> line.minOf { it.top } }.filter { it > max(below, 0.5f) }.minOrNull()
            return max(text + pad, foot?.let { it - pad } ?: 1f) to false
        }

        // Headings at the margin, or a centred "Section B": where a run of headings starts (with
        // a line or two of instructions allowed between them), as (page, top, line height).
        val heads = mutableListOf<Pair<Int, Float>>()
        val groups = mutableListOf<Triple<Int, Float, Float>>()
        for (p in pages.indices) {
            if (body[p].isEmpty()) continue
            val left = body[p].minOf { line -> line.minOf { it.left } }
            val right = body[p].maxOf { line -> line.maxOf { it.right } }
            var group: Triple<Int, Float, Float>? = null
            var since = 0
            for (line in body[p]) {
                val t = text(line)
                val centred = section.containsMatchIn(t) && line.size <= 6
                if (heading.containsMatchIn(t) && (centred || line.first().left - left <= HEAD_MARGIN * (right - left))) {
                    if (group == null || since > HEAD_GAP) group = Triple(p, line.minOf { it.top }, line.map { it.bottom - it.top }.average().toFloat())
                    heads += p to line.minOf { it.top }
                    groups += group
                    since = 0
                } else {
                    since++
                }
            }
        }

        /** Above the last run of headings between (page, top) [a] and [b], if there is one there. */
        fun cut(a: Pair<Int, Float>, b: Pair<Int, Float>): Pair<Int, Float>? {
            val last = heads.indices.lastOrNull { h ->
                before(a.first, a.second + 1e-4f, heads[h].first, heads[h].second) && before(heads[h].first, heads[h].second, b.first, b.second - 1e-4f)
            } ?: return null
            val (p, top, height) = groups[last]
            if (!before(a.first, a.second + 1e-4f, p, top)) return null
            return p to top - height * PAD
        }

        val parts = mutableListOf<Part>()
        val first = starts.first()
        if (first.number > 1) {
            var p = 0
            while (p < first.page && (empty(p) || cover.containsMatchIn(body[p].joinToString(" ") { text(it) }))) p++
            if (first.page - p <= LEAD_PAGES) {
                val pad = first.lineHeight * PAD
                for (q in p..first.page) {
                    if (empty(q)) continue
                    val top = textTop(q) - pad
                    val bottom = if (q == first.page) first.top - pad else foot(q, -1f, pad).first
                    if (bottom - top > MIN_PART) parts += Part(q, 0, top.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
                }
            }
        }
        // A heading between two questions ("Section B", "Read the passage below", "A 组 阅读下文，完成
        // 第16至20题") starts what the next one needs: the passage under it goes with that question.
        val cuts = mutableListOf<Pair<Int, Float>?>()
        val lead = parts.firstOrNull()
        val leadCut = lead?.let { cut(it.page to it.top + LEAD_SKIP, first.page to first.top) }
        if (lead != null && leadCut != null) {
            // The questions before the first one found end at a heading over the first one's passage.
            val kept = parts.filter { before(it.page, it.top, leadCut.first, leadCut.second) }
                .map { if (it.page == leadCut.first) it.copy(bottom = min(it.bottom, leadCut.second.coerceIn(0f, 1f))) else it }
                .filter { it.bottom - it.top > MIN_PART }
            parts.clear()
            parts += kept
        }
        cuts += leadCut
        starts.zipWithNext { a, b -> cuts += cut(a.page to a.top, b.page to b.top) }

        for ((i, start) in starts.withIndex()) {
            val next = starts.getOrNull(i + 1)
            val pad = start.lineHeight * PAD
            val begin = cuts[i] ?: (start.page to start.top - pad)
            val end = if (next == null) null else cuts[i + 1] ?: (next.page to next.top - next.lineHeight * PAD)
            var p = begin.first
            while (true) {
                if (!empty(p)) {
                    val top = if (p == begin.first) begin.second else textTop(p) - pad
                    val (bottom, ended) = if (end != null && p == end.first) {
                        end.second to false
                    } else {
                        foot(p, if (p == begin.first) begin.second + pad else -1f, pad)
                    }
                    if (bottom - top > MIN_PART) parts += Part(p, start.number, top.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f))
                    if (ended) break
                }
                p++
                if (p >= pages.size) break
                if (end != null) {
                    if (p > end.first) break
                } else if (p > start.page + LAST_PAGES || empty(p) || candidates(p, pages[p]).isNotEmpty()) {
                    break
                }
            }
        }
        return parts
    }

    /** Whether (page [p], [y] down it) comes before (page [q], [z]) in reading order. */
    private fun before(p: Int, y: Float, q: Int, z: Float) = p < q || (p == q && y < z)

    /**
     * The longest run of [candidates] numbered going up, in reading order: up by one, or by a
     * few more where a number was missed, which costs the run a little. Empty under two.
     */
    private fun run(candidates: List<Start>): List<Start> {
        if (candidates.isEmpty()) return emptyList()
        val best = FloatArray(candidates.size) { 1f }
        val previous = IntArray(candidates.size) { -1 }
        for (i in candidates.indices) {
            for (j in 0 until i) {
                val gap = candidates[i].number - candidates[j].number - 1
                if (gap !in 0..MAX_GAP) continue
                val score = best[j] + 1 - GAP_COST * gap
                // Ties go to the one in the same column, then to the nearer one.
                val closer = previous[i] < 0 ||
                    abs(candidates[j].left - candidates[i].left) <= abs(candidates[previous[i]].left - candidates[i].left) + 0.005f
                if (score > best[i] || (score == best[i] && closer)) {
                    best[i] = score
                    previous[i] = j
                }
            }
        }
        val end = best.indices.maxByOrNull { best[it] } ?: return emptyList()
        val run = generateSequence(end) { previous[it].takeIf { p -> p >= 0 } }.map { candidates[it] }.toList().reversed()
        return if (run.size < MIN_QUESTIONS) emptyList() else run
    }

    /**
     * Lines of [words] that open with a question number, top to bottom, each with whether it
     * opens a later part of its question ("9b)", "9(c)").
     */
    private fun candidates(page: Int, words: List<PdfWord>): List<Pair<Start, Boolean>> {
        if (words.isEmpty()) return emptyList()
        val left = words.minOf { it.left }
        val right = words.maxOf { it.right }
        return lines(words).mapNotNull { line ->
            val first = line.first()
            // At the margin, not in the middle of a line or far indented.
            if (first.left - left > MARGIN * (right - left)) return@mapNotNull null
            val number = number(line) ?: return@mapNotNull null
            Start(page, number, line.minOf { it.top }, line.map { it.bottom - it.top }.average().toFloat(), first.left) to
                laterPart.containsMatchIn(clean(first.text))
        }
    }

    /** The question number a line opens with, if it does. */
    private fun number(line: List<PdfWord>): Int? {
        val words = line.map { clean(it.text) }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val rest = words.drop(1)
        numbered.matchEntire(words[0])?.let { m ->
            val n = m.groupValues[2].toInt()
            return n.takeIf { it in 1..MAX_NUMBER && !counting(n, rest) && !answerLine(rest) }
        }
        // "Q 3" or "Question 3." as two words.
        if (rest.isNotEmpty() && word.matches(words[0])) {
            val m = numbered.matchEntire(rest[0])?.takeIf { it.groupValues[1].isEmpty() } ?: return null
            val n = m.groupValues[2].toInt()
            return n.takeIf { it in 1..MAX_NUMBER && !counting(n, rest.drop(1)) }
        }
        return null
    }

    /** [rest] counts on from [n]: the head of a table of marks ("Question 1 2 3"), a scale. */
    private fun counting(n: Int, rest: List<String>) =
        rest.size >= 2 && rest.take(3).withIndex().all { (k, r) -> r == (n + 1 + k).toString() }

    /** A numbered line to write an answer on: nothing but dots after the number, and perhaps its marks ("[2]"). */
    private fun answerLine(rest: List<String>) = rest.filterNot { marks.matches(it) }.let { it.isNotEmpty() && it.all { r -> dots.matches(r) } }

    /**
     * For each page, its lines that are the paper's furniture rather than its questions: near
     * the top or the bottom, a page number, "Turn over", or a running head or foot that comes
     * back on many pages (numbers in it aside).
     */
    private fun furniture(pages: List<List<List<PdfWord>>>): List<Set<List<PdfWord>>> {
        fun atEdge(line: List<PdfWord>) = line.minOf { it.top } < EDGE || line.maxOf { it.bottom } > 1 - EDGE
        fun key(line: List<PdfWord>) = text(line).lowercase().replace(digits, "#")
        val seen = pages.flatMap { page -> page.filter(::atEdge).map(::key).toSet() }.groupingBy { it }.eachCount()
        val often = max(2, pages.size / 4)
        return pages.map { page ->
            page.filter { atEdge(it) && (folio.matches(text(it)) || (seen[key(it)] ?: 0) >= often) }.toSet()
        }
    }

    private fun text(line: List<PdfWord>) = line.joinToString(" ") { clean(it.text) }.trim()

    private fun clean(text: String) = text.replace(invisible, "").trim()

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

    /**
     * An optional "Q" or "Question", the number, then an optional "." ")" or ":", or the first
     * part's letter run on: "3(a)", "3.(a)(i)", "3a)".
     */
    private val numbered = Regex("""^((?:Q|Question)\.?\s*)?\(?(\d{1,2})([.):]?(?:\([a-z]{1,4}\))+|[a-h]\)|[.):])?$""", RegexOption.IGNORE_CASE)
    private val word = Regex("""^(Q|Question)\.?$""", RegexOption.IGNORE_CASE)
    private val laterPart = Regex("""^\d{1,2}[.)]?\(?[b-h]\)?""")
    private val marks = Regex("""^[\[(]\d{1,2}[\])]$""")
    private val dots = Regex("""^[.…_—-]+$""")
    private val invisible = Regex("[​‌‍﻿]")
    private val digits = Regex("""\d+""")
    private val folio = Regex("""^([^\p{L}\p{N}]*\d{1,3}[^\p{L}\p{N}]*|page \d+( of \d+)?|.*\[?turn over\]?)$""", RegexOption.IGNORE_CASE)
    private val blankPage = Regex("""blank page|intentionally (been )?left blank|this page is (intentionally )?(left )?blank""", RegexOption.IGNORE_CASE)
    /** Words of a paper's cover: its instructions and the boxes for the candidate's details. */
    private val cover = Regex(
        """instructions|index number|register number|candidate|printed pages|answer all|this (paper|document|question paper) consists|姓名|班级|说明|考生|nama|arahan|for examiner""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * A heading that starts a section or the passage its questions are on: "Section B", "Text 2",
     * "Read the following passage", "Questions 16-20", a Chinese "三 " or "A 组", Malay "Soalan 5".
     */
    private val heading = Regex(
        """^((section|part|bahagian)\s+[A-H1-9]\b|(text|passage|source|extract|article|poem)\s+[A-H1-9]\b(?![\s,]+[A-H0-9]\b)(?!.*/)|""" +
            """read (the|this|these) (following|passages?|texts?|extracts?|articles?|poems?|sources?)\b|questions?\s+\d+\s*(-|–|to|and)\s*\d+|""" +
            """soalan\s+\d+|baca\b|[一二三四五六七八九十]{1,2}[、\s\u3000]|[A-F][\s\u3000]*组)""",
        RegexOption.IGNORE_CASE,
    )
    private val section = Regex("""^(section|part|bahagian)\s+[A-H1-9]\b""", RegexOption.IGNORE_CASE)
    private val endOfPaper = Regex("""end of (the )?(question )?paper|end of (section|test|exam)""", RegexOption.IGNORE_CASE)

    private const val MIN_QUESTIONS = 2
    private const val MAX_NUMBER = 60
    /** A question's number starts within this share of the text's width from its left edge. */
    private const val MARGIN = 0.12f
    /** How far from the question numbers' column (page widths) a number may stand and still count. */
    private const val COLUMN = 0.04f
    /** Numbers that may be missed between two questions found, and what each missed costs the run. */
    private const val MAX_GAP = 3
    private const val GAP_COST = 0.5f
    /** How far from the top or bottom (page heights) a page's running head and foot are. */
    private const val EDGE = 0.1f
    /** Room left above a question and below it, in its line heights; parts thinner than [MIN_PART] are dropped. */
    private const val PAD = 0.8f
    private const val MIN_PART = 0.02f
    /** Pages the last question may run on over before the paper is taken to have ended. */
    private const val LAST_PAGES = 3
    /** Pages the questions before the first one found may take; more, and it's not taken to be questions. */
    private const val LEAD_PAGES = 4
    /** How far into the stretch before the first question found its own headings may be (page heights). */
    private const val LEAD_SKIP = 0.03f
    /** A heading starts this share of the text's width from its left edge, at most. */
    private const val HEAD_MARGIN = 0.15f
    /** Lines of instructions there may be between two headings of one run. */
    private const val HEAD_GAP = 2
}
