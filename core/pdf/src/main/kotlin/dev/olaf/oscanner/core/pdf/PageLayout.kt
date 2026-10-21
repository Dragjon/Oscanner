package dev.olaf.oscanner.core.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One piece of a page, in reading order, for exports that rebuild the page's layout. */
sealed interface LayoutBlock {
    /** Where it sits on the page, top edge, normalized: pictures are slotted in by it. */
    val top: Float
}

enum class ParagraphStyle { Heading1, Heading2, Body }

/**
 * A paragraph: its lines joined into one run of text. [size] is its letter height relative to
 * the page's body text (1 = body), so an export can scale the font.
 */
data class LayoutParagraph(
    val text: String,
    val style: ParagraphStyle,
    val centered: Boolean,
    val size: Float,
    override val top: Float,
) : LayoutBlock

/** A picture cut from the page: a JPEG, its size in pixels, and its width as a share of the page's. */
data class LayoutPicture(
    val jpeg: ByteArray,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val widthShare: Float,
    override val top: Float,
) : LayoutBlock

/**
 * Rebuilds the flow of a page from the words read off it: words on one line become lines,
 * lines close together with the same letter size become paragraphs, and a short paragraph in
 * clearly bigger letters is a heading. Lines centred on the text block stay centred.
 */
object PageLayout {

    fun paragraphs(words: List<PdfWord>): List<LayoutParagraph> {
        val clean = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (clean.isEmpty()) return emptyList()
        val lines = lines(clean)
        // Body text is what most words are set in, however few lines a heading takes.
        val body = clean.map { it.bottom - it.top }.sorted()[clean.size / 2]
        // The text block's margins, from lines in body text: a big title can be wider than the
        // column, and every line under it would look short and nothing would look centred.
        val bodyLines = lines.filter { it.height / body < HEADING2 }.ifEmpty { lines }
        val textLeft = bodyLines.minOf { it.left }
        val textRight = bodyLines.maxOf { it.right }
        val textMiddle = (textLeft + textRight) / 2
        val textWidth = textRight - textLeft

        // Centred: clear of both margins, by about the same; or, for a title wider than a ragged
        // column, on the middle of the page and clear of the column's left edge.
        fun centered(line: Line): Boolean {
            val middle = (line.left + line.right) / 2
            val clearLeft = line.left - textLeft > CENTRE_MARGIN * textWidth
            return clearLeft && (
                (abs(middle - textMiddle) < CENTRE_SLACK * textWidth && textRight - line.right > CENTRE_MARGIN * textWidth) ||
                    abs(middle - 0.5f) < CENTRE_SLACK
                )
        }

        // The page's own spacing between lines: a paragraph break is clearly more than it.
        val spacing = lines.zipWithNext { a, b -> (b.top - a.bottom) / min(a.height, b.height) }
            .filter { it > -0.5f && it < 2f }.sorted().let { it.getOrNull(it.size / 2) ?: 0f }
        val breakGap = max(PARAGRAPH_GAP, min(spacing + PARAGRAPH_EXTRA, MAX_LINE_GAP))

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
            LayoutParagraph(text, style, group.all(::centered), size, group.first().top)
        }
    }

    private class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val height get() = bottom - top
    }

    /**
     * Words sharing a line, top to bottom. A wide gap within a row (a second column, a form's
     * label and value far apart) splits it into separate lines, read left to right.
     */
    private fun lines(words: List<PdfWord>): List<Line> {
        val height = words.map { it.bottom - it.top }.sorted()[words.size / 2]
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
        return rows.flatMap { row ->
            val sorted = row.sortedBy { it.left }
            val pieces = mutableListOf(mutableListOf(sorted.first()))
            for (w in sorted.drop(1)) {
                if (w.left - pieces.last().last().right > COLUMN_GAP * height) pieces += mutableListOf(w) else pieces.last() += w
            }
            pieces.map { p -> Line(p.joinToString(" ") { it.text }, p.minOf { it.left }, p.minOf { it.top }, p.maxOf { it.right }, p.maxOf { it.bottom }) }
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
}
