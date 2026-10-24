package dev.olaf.oscanner.core.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One piece of a page, in reading order, for exports that rebuild the page's layout. */
sealed interface LayoutBlock {
    /** Where it sits on the page, top edge, normalized: pictures are slotted in by it. */
    val top: Float
    /** 0 across the page; 1 or 2 in the left or right column of a part set in two columns. */
    val column: Int
}

enum class ParagraphStyle { Heading1, Heading2, Body }

/**
 * A paragraph: its lines joined into one run of text. [size] is its letter height relative to
 * the page's body text (1 = body), so an export can scale the font. [left], [right] and
 * [bottom] close its box with [top], and [lineHeight] is its letters' height, all normalized:
 * enough to set other words in its place.
 */
data class LayoutParagraph(
    val text: String,
    val style: ParagraphStyle,
    val centered: Boolean,
    val size: Float,
    override val top: Float,
    override val column: Int = 0,
    val left: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = top,
    val lineHeight: Float = 0f,
) : LayoutBlock

/**
 * A picture cut from the page: a JPEG, its size in pixels, its width as a share of the page's,
 * and its left edge (normalized), which says which column it's in.
 */
data class LayoutPicture(
    val jpeg: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val widthShare: Float,
    override val top: Float,
    val left: Float = 0f,
    override val column: Int = 0,
) : LayoutBlock

/**
 * Rebuilds the flow of a page from the words read off it: words on one line become lines,
 * lines close together with the same letter size become paragraphs, and a short paragraph in
 * clearly bigger letters is a heading. Lines centred on the text block stay centred. Text set
 * in two columns is read down the left column, then the right.
 */
object PageLayout {

    fun paragraphs(words: List<PdfWord>): List<LayoutParagraph> = blocks(words).filterIsInstance<LayoutParagraph>()

    /**
     * The page's text line by line in reading order when it's set in two columns: what runs
     * across, then the left column, then the right, a blank line between parts. Null for a page
     * without columns, whose text reads row by row as it is.
     */
    fun columnText(words: List<PdfWord>): String? {
        val clean = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (clean.isEmpty()) return null
        val body = clean.map { it.bottom - it.top }.sorted()[clean.size / 2]
        val gutter = gutter(clean, body) ?: return null
        val parts = parts(lines(clean, gutter), emptyList(), gutter)
        if (parts.none { it.column != 0 }) return null
        return parts.joinToString("\n\n") { part -> part.lines.joinToString("\n") { it.text } }
    }

    /** The page's paragraphs with [pictures] slotted in where they sit, all in reading order. */
    fun blocks(words: List<PdfWord>, pictures: List<LayoutPicture> = emptyList()): List<LayoutBlock> {
        val clean = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (clean.isEmpty()) return pictures.sortedBy { it.top }
        // Body text is what most words are set in, however few lines a heading takes.
        val body = clean.map { it.bottom - it.top }.sorted()[clean.size / 2]
        val gutter = gutter(clean, body)
        val lines = lines(clean, gutter)
        // The text block's margins, from lines in body text: a big title can be wider than the
        // column, and every line under it would look short and nothing would look centred.
        fun frameOf(of: List<Line>): Pair<Float, Float> {
            val bodyLines = of.filter { it.height / body < HEADING2 }.ifEmpty { of }
            return bodyLines.minOf { it.left } to bodyLines.maxOf { it.right }
        }
        val page = frameOf(lines)
        val parts = parts(lines, pictures, gutter)

        // The page's own spacing between lines: a paragraph break is clearly more than it.
        val spacing = parts.flatMap { part -> part.lines.zipWithNext { a, b -> (b.top - a.bottom) / min(a.height, b.height) } }
            .filter { it > -0.5f && it < 2f }.sorted().let { it.getOrNull(it.size / 2) ?: 0f }
        val breakGap = max(PARAGRAPH_GAP, min(spacing + PARAGRAPH_EXTRA, MAX_LINE_GAP))

        return parts.flatMap { part ->
            val (textLeft, textRight) = if (part.column == 0 || part.lines.isEmpty()) page else frameOf(part.lines)
            val paragraphs = paragraphs(part.lines, textLeft, textRight, body, breakGap, wholePage = part.column == 0)
                .map { it.copy(column = part.column) }
            val blocks = mutableListOf<LayoutBlock>().apply { addAll(paragraphs) }
            for (picture in part.pictures.sortedBy { it.top }) {
                val at = blocks.indexOfFirst { it.top > picture.top }.takeIf { it >= 0 } ?: blocks.size
                blocks.add(at, picture.copy(column = part.column))
            }
            blocks
        }
    }

    private fun paragraphs(
        lines: List<Line>,
        textLeft: Float,
        textRight: Float,
        body: Float,
        breakGap: Float,
        wholePage: Boolean,
    ): List<LayoutParagraph> {
        if (lines.isEmpty()) return emptyList()
        val textMiddle = (textLeft + textRight) / 2
        val textWidth = textRight - textLeft

        // Centred: clear of both margins, by about the same; or, for a title wider than a ragged
        // column, on the middle of the page and clear of the column's left edge.
        fun centered(line: Line): Boolean {
            val middle = (line.left + line.right) / 2
            val clearLeft = line.left - textLeft > CENTRE_MARGIN * textWidth
            return clearLeft && (
                (abs(middle - textMiddle) < CENTRE_SLACK * textWidth && textRight - line.right > CENTRE_MARGIN * textWidth) ||
                    (wholePage && abs(middle - 0.5f) < CENTRE_SLACK)
                )
        }

        val groups = mutableListOf<MutableList<Line>>()
        for (line in lines) {
            val group = groups.lastOrNull()
            val previous = group?.last()
            val joins = previous != null &&
                line.top - previous.bottom < breakGap * min(line.height, previous.height) &&
                max(line.height, previous.height) / min(line.height, previous.height) < SAME_SIZE &&
                centered(line) == centered(previous) &&
                // A line that stops well short of the margin ends its paragraph.
                (centered(previous) || previous.right > textRight - SHORT_LINE * textWidth) &&
                // So does a new line indented further than the one before.
                line.left - previous.left < INDENT * textWidth
            if (joins) group!! += line else groups += mutableListOf(line)
        }
        return groups.map { group ->
            val size = group.map { it.height }.average().toFloat() / body
            val text = join(group.map { it.text })
            // A heading is short as well as big: a line of large handwriting is still body text.
            val short = group.size <= HEADING_LINES && text.split(' ').size <= HEADING_WORDS &&
                group.all { it.right - it.left < HEADING_WIDTH }
            val style = when {
                short && size >= HEADING1 -> ParagraphStyle.Heading1
                short && size >= HEADING2 -> ParagraphStyle.Heading2
                else -> ParagraphStyle.Body
            }
            LayoutParagraph(
                text, style, group.all(::centered), size, group.first().top,
                left = group.minOf { it.left }, right = group.maxOf { it.right }, bottom = group.maxOf { it.bottom },
                lineHeight = group.map { it.height }.average().toFloat(),
            )
        }
    }

    private class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val side: Int) {
        val height get() = bottom - top
    }

    /** A stretch of the page read as one: across it ([column] 0), or one column of a two-column part. */
    private class Part(val column: Int, val lines: List<Line>, val pictures: List<LayoutPicture>)

    /**
     * Where the page splits into two columns, if it does: an upright strip in the middle of the
     * text that few rows of body text cross (only what runs across both columns, such as a title
     * or an introduction), with columns of similar width filled with text on either side. A
     * form's labels and their answers don't count: the labels are short and their column narrow.
     */
    private fun gutter(words: List<PdfWord>, body: Float): Float? {
        val bodyWords = words.filter { (it.bottom - it.top) / body < HEADING2 }
        if (bodyWords.size < MIN_COLUMN_WORDS) return null
        val left = bodyWords.minOf { it.left }
        val right = bodyWords.maxOf { it.right }
        val width = right - left
        val rows = rows(bodyWords)
        // For each slice of the text's width, the rows with a word over it.
        val crossed = IntArray(GUTTER_BINS)
        for (row in rows) {
            val over = BooleanArray(GUTTER_BINS)
            for (w in row) {
                val from = ((w.left - left) / width * GUTTER_BINS).toInt().coerceIn(0, GUTTER_BINS - 1)
                val to = ((w.right - left) / width * GUTTER_BINS).toInt().coerceIn(0, GUTTER_BINS - 1)
                for (b in from..to) over[b] = true
            }
            over.forEachIndexed { b, on -> if (on) crossed[b]++ }
        }
        // The widest run of slices in the middle crossed by few rows.
        val most = (rows.size * CROSSING_ROWS).toInt()
        var best: IntRange? = null
        var b = (GUTTER_BINS * GUTTER_ZONE).toInt()
        val end = (GUTTER_BINS * (1 - GUTTER_ZONE)).toInt()
        while (b < end) {
            if (crossed[b] > most) { b++; continue }
            val from = b
            while (b < end && crossed[b] <= most) b++
            if (best == null || b - from > best.last - best.first + 1) best = from until b
        }
        val run = best ?: return null
        if ((run.last - run.first + 1) * width / GUTTER_BINS < MIN_GUTTER * body) return null
        val g = left + (run.first + run.last + 1) / 2f * width / GUTTER_BINS

        // Rows that keep clear of it, made again on each side (the columns' lines needn't sit
        // level), and how much of their side's width they fill.
        val clear = rows.filterNot { runsAcross(it, g) }.flatten()
        val leftRows = rows(clear.filter { it.right <= g })
        val rightRows = rows(clear.filter { it.left >= g })
        if (leftRows.size < MIN_COLUMN_LINES || rightRows.size < MIN_COLUMN_LINES) return null
        val leftWidth = g - left
        val rightWidth = right - g
        if (max(leftWidth, rightWidth) / min(leftWidth, rightWidth) > COLUMN_RATIO) return null
        fun fill(of: List<List<PdfWord>>, from: (List<PdfWord>) -> Float, to: (List<PdfWord>) -> Float, side: Float) =
            of.map { (to(it) - from(it)) / side }.sorted()[of.size / 2]
        if (fill(leftRows, { r -> r.minOf { it.left } }, { r -> r.maxOf { it.right } }, leftWidth) < COLUMN_FILL) return null
        if (fill(rightRows, { r -> r.minOf { it.left } }, { r -> r.maxOf { it.right } }, rightWidth) < COLUMN_FILL) return null
        // Side by side, not one above the other.
        val overlap = min(leftRows.maxOf { r -> r.maxOf { it.bottom } }, rightRows.maxOf { r -> r.maxOf { it.bottom } }) -
            max(leftRows.minOf { r -> r.minOf { it.top } }, rightRows.minOf { r -> r.minOf { it.top } })
        return g.takeIf { overlap > 0f }
    }

    /**
     * Cuts the page into parts at what runs across the [gutter] (a title, a wide picture): what
     * lies between two such is a two-column part if both columns hold some lines, and is read
     * across otherwise. Without a gutter the page is one part.
     */
    private fun parts(lines: List<Line>, pictures: List<LayoutPicture>, gutter: Float?): List<Part> {
        if (gutter == null) return listOf(Part(0, lines, pictures))
        fun side(p: LayoutPicture) = when {
            p.left + p.widthShare <= gutter -> 1
            p.left >= gutter -> 2
            else -> 0
        }
        val items = (lines.map { it.top to (it as Any) } + pictures.map { it.top to (it as Any) }).sortedBy { it.first }.map { it.second }
        val parts = mutableListOf<Part>()
        val split = mutableListOf<Any>()
        fun addAcross(of: List<Any>) {
            val last = parts.lastOrNull()
            val newLines = of.filterIsInstance<Line>()
            val newPictures = of.filterIsInstance<LayoutPicture>()
            if (last != null && last.column == 0) parts[parts.size - 1] = Part(0, last.lines + newLines, last.pictures + newPictures)
            else parts += Part(0, newLines, newPictures)
        }
        fun flushSplit() {
            if (split.isEmpty()) return
            val sideOf = { item: Any -> if (item is Line) item.side else side(item as LayoutPicture) }
            val leftLines = split.count { it is Line && it.side == 1 }
            val rightLines = split.count { it is Line && it.side == 2 }
            if (leftLines >= MIN_PART_LINES && rightLines >= MIN_PART_LINES) {
                for (column in 1..2) {
                    val of = split.filter { sideOf(it) == column }
                    parts += Part(column, of.filterIsInstance<Line>(), of.filterIsInstance<LayoutPicture>())
                }
            } else {
                addAcross(split.toList())
            }
            split.clear()
        }
        for (item in items) {
            val crosses = if (item is Line) item.side == 0 else side(item as LayoutPicture) == 0
            if (crosses) {
                flushSplit()
                addAcross(listOf(item))
            } else {
                split += item
            }
        }
        flushSplit()
        return parts.filter { it.lines.isNotEmpty() || it.pictures.isNotEmpty() }
    }

    /**
     * Whether a row runs over the [gutter]: a word lies over it, or the words either side of it
     * are only a word space apart (a title whose space happens to fall there).
     */
    private fun runsAcross(row: List<PdfWord>, gutter: Float): Boolean {
        if (row.any { it.left < gutter && it.right > gutter }) return true
        val before = row.filter { it.right <= gutter }.maxByOrNull { it.right } ?: return false
        val after = row.filter { it.left >= gutter }.minByOrNull { it.left } ?: return false
        return after.left - before.right < MIN_GUTTER * max(before.bottom - before.top, after.bottom - after.top)
    }

    /** Words grouped into rows by overlapping height, top to bottom. */
    private fun rows(words: List<PdfWord>): List<List<PdfWord>> {
        val rows = mutableListOf<MutableList<PdfWord>>()
        for (w in words.sortedBy { (it.top + it.bottom) / 2 }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val top = row.minOf { it.top }
                val bottom = row.maxOf { it.bottom }
                val overlap = min(bottom, w.bottom) - max(top, w.top)
                if (overlap > 0.5f * min(bottom - top, w.bottom - w.top)) {
                    row += w
                    continue
                }
            }
            rows += mutableListOf(w)
        }
        return rows
    }

    /**
     * Words sharing a line, top to bottom. A wide gap within a row (a second column, a form's
     * label and value far apart) splits it into separate lines, read left to right. With a
     * [gutter], rows that keep clear of it are made on each side by themselves, so columns whose
     * lines don't sit level still read line by line; a row with a word over it runs across.
     */
    private fun lines(words: List<PdfWord>, gutter: Float?): List<Line> {
        val height = words.map { it.bottom - it.top }.sorted()[words.size / 2]
        val rows = rows(words).map { 0 to it }.let { all ->
            if (gutter == null) return@let all
            val (across, clear) = all.partition { (_, row) -> runsAcross(row, gutter) }
            val clearWords = clear.flatMap { it.second }
            (across + rows(clearWords.filter { it.right <= gutter }).map { 1 to it } + rows(clearWords.filter { it.left >= gutter }).map { 2 to it })
                .sortedBy { (_, row) -> row.minOf { it.top } }
        }
        return rows.flatMap { (side, row) ->
            val sorted = row.sortedBy { it.left }
            val pieces = mutableListOf(mutableListOf(sorted.first()))
            for (w in sorted.drop(1)) {
                if (w.left - pieces.last().last().right > COLUMN_GAP * height) pieces += mutableListOf(w) else pieces.last() += w
            }
            pieces.map { p -> Line(p.joinToString(" ") { it.text }, p.minOf { it.left }, p.minOf { it.top }, p.maxOf { it.right }, p.maxOf { it.bottom }, side) }
        }
    }

    /** Lines of one paragraph as running text; a word broken over two lines with a hyphen is mended. */
    private fun join(lines: List<String>): String = lines.fold("") { acc, line ->
        when {
            acc.isEmpty() -> line
            acc.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> acc.dropLast(1) + line
            else -> "$acc $line"
        }
    }

    /** Lines further apart than this share of their letter height start a new paragraph... */
    private const val PARAGRAPH_GAP = 0.9f
    /** ...or, on widely spaced pages, this much more than the usual gap, up to the most. */
    private const val PARAGRAPH_EXTRA = 0.4f
    private const val MAX_LINE_GAP = 1.5f
    /** Letter heights within this ratio are the same size. */
    private const val SAME_SIZE = 1.4f
    /** A line ending this share of the text width short of the margin ends a paragraph. */
    private const val SHORT_LINE = 0.15f
    /** Indent, as a share of the text width, that starts a paragraph. */
    private const val INDENT = 0.04f
    /** A gap this many letter heights wide within a row splits it in two. */
    private const val COLUMN_GAP = 3f
    private const val CENTRE_SLACK = 0.04f
    private const val CENTRE_MARGIN = 0.08f
    private const val HEADING_LINES = 2
    private const val HEADING_WORDS = 12
    /** A heading is narrower than this share of the page. */
    private const val HEADING_WIDTH = 0.7f
    private const val HEADING1 = 1.7f
    private const val HEADING2 = 1.4f

    /** Slices the text's width is cut into when looking for the gap between columns. */
    private const val GUTTER_BINS = 200
    /** The gap is looked for between this share of the text's width from either side. */
    private const val GUTTER_ZONE = 0.25f
    /** At most this share of the rows may cross it: what runs across both columns. */
    private const val CROSSING_ROWS = 0.3f
    /** The gap is at least this many letter heights wide (a word space is about a third). */
    private const val MIN_GUTTER = 0.6f
    private const val MIN_COLUMN_WORDS = 16
    /** Each column has at least this many rows of its own... */
    private const val MIN_COLUMN_LINES = 4
    /** ...the wider at most this many times the other... */
    private const val COLUMN_RATIO = 2f
    /** ...and its rows, as a rule, fill at least this share of its width. */
    private const val COLUMN_FILL = 0.55f
    /** A part between two things running across is read in columns when each has this many lines. */
    private const val MIN_PART_LINES = 2
}
