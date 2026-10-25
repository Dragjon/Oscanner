package dev.olaf.oscanner.core.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.olaf.oscanner.core.imaging.PrintEraser
import dev.olaf.oscanner.core.pdf.LayoutParagraph
import dev.olaf.oscanner.core.pdf.ParagraphStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A page with its paragraphs set in another language where they were: each paragraph is
 * painted over in the paper's colour around it and its translation written into its place in
 * the colour of its ink, as large as the page's letters were, smaller when the translation
 * needs more room than the paragraph had, and with serifs when the page's print has them.
 * Where the print sits on a picture or a gradient, only its letters are taken off, filled from
 * the picture around them, and the translation gets a thin edge in the background's colour to
 * stand out from it.
 */
object TranslatedPage {

    /** [page] with each of [paragraphs] replaced by the text at the same index of [texts]. */
    fun draw(page: Bitmap, paragraphs: List<LayoutParagraph>, texts: List<String>): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val placed = paragraphs.filter { it.lineHeight > 0f && it.right > it.left }
        if (placed.isEmpty()) return out
        val frameLeft = placed.minOf { it.left } * w
        val frameRight = placed.maxOf { it.right } * w
        // Colours come from the page as it was, before any paragraph is painted over.
        val colours = placed.map { inks(page, it) }
        val busy = placed.map { busy(page, it) }
        // Print on pictures is taken off first, all at once, leaving the picture.
        PrintEraser.erase(
            out,
            placed.indices.filter { busy[it] && texts.getOrNull(paragraphs.indexOf(placed[it])) != null }.map { i ->
                val p = placed[i]
                PrintEraser.Print((p.left * w).toInt(), (p.top * h).toInt(), (p.right * w).toInt(), (p.bottom * h).toInt(), colours[i].second, p.lineHeight * h)
            },
        )
        val serif = serif(page, placed, colours)
        val face = if (serif) Typeface.SERIF else Typeface.DEFAULT
        val fill = Paint().apply { style = Paint.Style.FILL }
        paragraphs.zip(texts).forEach { (p, text) ->
            val i = placed.indexOf(p).takeIf { it >= 0 } ?: return@forEach
            val (paper, ink) = colours[i]
            val line = p.lineHeight * h
            val box = RectF(p.left * w, p.top * h, p.right * w, p.bottom * h)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ink
                typeface = if (p.style == ParagraphStyle.Body) face else Typeface.create(face, Typeface.BOLD)
            }
            val oneLine = box.height() < line * 1.6f
            val alignment = when {
                p.centered -> Layout.Alignment.ALIGN_CENTER
                // A date or a sign-off set against the right margin stays there; a paragraph in
                // the right-hand column doesn't.
                oneLine && p.left * w - frameLeft > (frameRight - frameLeft) / 2 && frameRight - box.right < line -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            // Room to the side on its row, and below down to the next paragraph under it.
            val others = placed.filter { it !== p }
            val sameRow = others.filter { it.top * h < box.bottom && it.bottom * h > box.top }
            val roomRight = (sameRow.filter { it.left * w >= box.right }.minOfOrNull { it.left * w - line } ?: frameRight).coerceAtLeast(box.right)
            val roomLeft = (sameRow.filter { it.right * w <= box.left }.maxOfOrNull { it.right * w + line } ?: frameLeft).coerceAtMost(box.left)
            val roomBottom = (others.filter { it.top * h >= box.bottom && it.left * w < box.right && it.right * w > box.left }
                .minOfOrNull { it.top * h - line * GAP } ?: (h - line)).coerceAtLeast(box.bottom)
            val (from, to) = when (alignment) {
                Layout.Alignment.ALIGN_CENTER -> {
                    val half = min(box.centerX() - roomLeft, roomRight - box.centerX())
                    box.centerX() - half to box.centerX() + half
                }
                Layout.Alignment.ALIGN_OPPOSITE -> roomLeft to box.right
                else -> box.left to roomRight
            }
            val width = max(1, (to - from).toInt())
            // Each line of the paragraph keeps its own width; only a line that's run out of
            // paragraph spills into the room beside it.
            val wrapWidth = if (oneLine) width else max(1, box.width().toInt())
            var size = line * LETTER
            var layout = layout(text, paint, size, wrapWidth, alignment)
            while ((layout.height > roomBottom - box.top || widest(layout) > width) && size > line * LETTER * SMALLEST) {
                size *= SHRINK
                layout = layout(text, paint, size, wrapWidth, alignment)
            }
            val left = when (alignment) {
                Layout.Alignment.ALIGN_CENTER -> box.centerX() - wrapWidth / 2f
                Layout.Alignment.ALIGN_OPPOSITE -> box.right - wrapWidth
                else -> box.left
            }
            canvas.save()
            canvas.translate(left, box.top)
            if (busy[i]) {
                // Its edge first, in the background's colour, then the letters over it.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = paint.textSize * HALO
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = paper
                layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = ink
            } else {
                val pad = line * PAD
                fill.color = paper
                canvas.drawRect(box.left - left - pad, -pad, box.right - left + pad, box.height() + pad, fill)
                canvas.drawRect(leftmost(layout) - pad, -pad, leftmost(layout) + widest(layout) + pad, layout.height + pad, fill)
            }
            layout.draw(canvas)
            canvas.restore()
        }
        return out
    }

    private fun layout(text: String, paint: TextPaint, size: Float, width: Int, alignment: Layout.Alignment): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()
    }

    private fun widest(layout: StaticLayout) = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f

    private fun leftmost(layout: StaticLayout) = (0 until layout.lineCount).minOfOrNull { layout.getLineLeft(it) } ?: 0f

    /**
     * The paper's colour around [p] (the middle of what's just outside its box) and its ink's
     * (what inside the box differs most from the paper).
     */
    private fun inks(page: Bitmap, p: LayoutParagraph): Pair<Int, Int> {
        val w = page.width
        val h = page.height
        val margin = max(2f, p.lineHeight * h * 0.4f)
        val l = (p.left * w - margin).toInt().coerceIn(0, w - 1)
        val t = (p.top * h - margin).toInt().coerceIn(0, h - 1)
        val r = (p.right * w + margin).toInt().coerceIn(0, w - 1)
        val b = (p.bottom * h + margin).toInt().coerceIn(0, h - 1)
        val ring = mutableListOf<Int>()
        val step = max(1, (r - l + b - t) / 400)
        for (x in l..r step step) { ring += page.getPixel(x, t); ring += page.getPixel(x, b) }
        for (y in t..b step step) { ring += page.getPixel(l, y); ring += page.getPixel(r, y) }
        fun median(channel: (Int) -> Int) = ring.map(channel).sorted()[ring.size / 2]
        val paper = Color.rgb(median(Color::red), median(Color::green), median(Color::blue))

        val inside = mutableListOf<Int>()
        val x0 = (p.left * w).toInt().coerceIn(0, w - 1)
        val y0 = (p.top * h).toInt().coerceIn(0, h - 1)
        val x1 = (p.right * w).toInt().coerceIn(x0, w - 1)
        val y1 = (p.bottom * h).toInt().coerceIn(y0, h - 1)
        val innerStep = max(1, ((x1 - x0) * (y1 - y0) / 20_000.0).let { kotlin.math.sqrt(it) }.toInt())
        for (y in y0..y1 step innerStep) for (x in x0..x1 step innerStep) inside += page.getPixel(x, y)
        val paperLuma = luma(paper)
        val far = inside.sortedByDescending { abs(luma(it) - paperLuma) }.take(max(1, inside.size / 10))
        val ink = if (far.isEmpty() || abs(luma(far.first()) - paperLuma) < MIN_CONTRAST) {
            if (paperLuma > 128) Color.BLACK else Color.WHITE
        } else {
            Color.rgb(far.map(Color::red).average().toInt(), far.map(Color::green).average().toInt(), far.map(Color::blue).average().toInt())
        }
        return paper to ink
    }

    /** Whether the print of the body paragraphs in [placed] has serifs (see [PrintedFace]). */
    private fun serif(page: Bitmap, placed: List<LayoutParagraph>, colours: List<Pair<Int, Int>>): Boolean {
        val face = PrintedFace()
        val w = page.width
        val h = page.height
        placed.forEachIndexed { i, p ->
            if (p.style != ParagraphStyle.Body) return@forEachIndexed
            val (paper, ink) = colours[i]
            val x0 = (p.left * w).toInt().coerceIn(0, w - 1)
            val y0 = (p.top * h).toInt().coerceIn(0, h - 1)
            val x1 = (p.right * w).toInt().coerceIn(x0 + 1, w)
            val y1 = (p.bottom * h).toInt().coerceIn(y0 + 1, h)
            val pixels = IntArray((x1 - x0) * (y1 - y0))
            page.getPixels(pixels, 0, x1 - x0, x0, y0, x1 - x0, y1 - y0)
            val lumas = IntArray(pixels.size) { luma(pixels[it]) }
            face.add(lumas, x1 - x0, (luma(paper) + luma(ink)) / 2, light = luma(ink) > luma(paper), size = p.lineHeight * h * LETTER)
        }
        return face.serif == true
    }

    /**
     * Whether [p] sits on a picture or a gradient rather than plain paper or a plain box: the
     * colour just around it varies.
     */
    private fun busy(page: Bitmap, p: LayoutParagraph): Boolean {
        val w = page.width
        val h = page.height
        val margin = max(2f, p.lineHeight * h * 0.4f)
        val l = (p.left * w - margin).toInt().coerceIn(0, w - 1)
        val t = (p.top * h - margin).toInt().coerceIn(0, h - 1)
        val r = (p.right * w + margin).toInt().coerceIn(0, w - 1)
        val b = (p.bottom * h + margin).toInt().coerceIn(0, h - 1)
        val ring = mutableListOf<Int>()
        val step = max(1, (r - l + b - t) / 400)
        for (x in l..r step step) { ring += luma(page.getPixel(x, t)); ring += luma(page.getPixel(x, b)) }
        for (y in t..b step step) { ring += luma(page.getPixel(l, y)); ring += luma(page.getPixel(r, y)) }
        val mean = ring.average()
        val spread = kotlin.math.sqrt(ring.sumOf { (it - mean) * (it - mean) } / ring.size)
        return spread > BUSY
    }

    private fun luma(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    /** A word's box is about this share of the type size it was set in. */
    private const val LETTER = 0.95f
    /** Each step down in type size when a translation doesn't fit... */
    private const val SHRINK = 0.92f
    /** ...down to this share of the page's own. */
    private const val SMALLEST = 0.45f
    /** Painted over this far around a paragraph, in letter heights, to catch stray ink. */
    private const val PAD = 0.15f
    /** Kept clear above the next paragraph down, in letter heights. */
    private const val GAP = 0.3f
    private const val MIN_CONTRAST = 40
    /** Around print on plain paper the colour varies by less than this (luma, standard deviation). */
    private const val BUSY = 14.0
    /** The edge round letters set on a picture, as a share of their size. */
    private const val HALO = 0.14f
}
