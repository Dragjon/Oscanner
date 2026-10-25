package dev.olaf.oscanner.core.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import dev.olaf.oscanner.core.ocr.OcrResult
import dev.olaf.oscanner.core.ocr.OcrWord
import dev.olaf.oscanner.core.pdf.OfficeAlign
import dev.olaf.oscanner.core.pdf.OfficeBlock
import dev.olaf.oscanner.core.pdf.OfficeFile
import dev.olaf.oscanner.core.pdf.OfficePageBreak
import dev.olaf.oscanner.core.pdf.OfficeParagraph
import dev.olaf.oscanner.core.pdf.OfficePicture
import dev.olaf.oscanner.core.pdf.OfficeTable
import dev.olaf.oscanner.core.pdf.Presentation
import dev.olaf.oscanner.core.pdf.Sheet
import dev.olaf.oscanner.core.pdf.SlidePicture
import dev.olaf.oscanner.core.pdf.SlideTable
import dev.olaf.oscanner.core.pdf.SlideText
import dev.olaf.oscanner.core.pdf.WordDocument
import dev.olaf.oscanner.core.pdf.Workbook
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An Office file laid out as pages, the way PDF import gives them: a picture of each page, and
 * its text word by word with where each sits, exactly as the file has it. A Word document flows
 * onto pages its own size, with its headings, lists, tables and pictures; each slide of a deck
 * is a page the slide's shape, its boxes where the slide has them; each sheet of a workbook is a
 * grid over as many pages as it needs.
 */
internal object OfficePages {

    fun render(file: OfficeFile, dir: File): List<ImportedPage> = when (file) {
        is WordDocument -> Pages(dir, file.pageWidth, file.pageHeight).also { word(file, it) }.done()
        is Presentation -> slides(file, dir)
        is Workbook -> file.sheets.flatMap { sheet(it, dir) }
    }

    /** Pages being drawn, in points scaled to pixels at [DPI], and the words placed on each. */
    private class Pages(val dir: File, val widthPt: Float, val heightPt: Float) {
        val scale = min(DPI / 72f, MAX_SIDE / max(widthPt, heightPt))
        val w = (widthPt * scale).roundToInt().coerceAtLeast(1)
        val h = (heightPt * scale).roundToInt().coerceAtLeast(1)
        private val out = ArrayList<ImportedPage>()
        var bitmap: Bitmap? = null
        var canvas: Canvas? = null
        private val words = ArrayList<OcrWord>()
        private val lines = ArrayList<String>()

        fun start(background: Int = Color.WHITE): Canvas {
            finish()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(background)
            bitmap = b
            return Canvas(b).also { canvas = it }
        }

        fun current(): Canvas = canvas ?: start()

        /** The words of [layout]'s lines [from] until [to], drawn with its top left at [x], [y] (pixels). */
        fun words(layout: Layout, from: Int, to: Int, x: Float, y: Float) {
            for (line in from until to) {
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(line)
                val text = layout.text.subSequence(start, end).toString()
                val top = y + layout.getLineTop(line) - layout.getLineTop(from)
                val bottom = y + layout.getLineBottom(line) - layout.getLineTop(from)
                if (text.isNotBlank()) lines += text.trimEnd()
                for (m in WORD.findAll(text)) {
                    val left = x + layout.getPrimaryHorizontal(start + m.range.first)
                    val right = if (start + m.range.last + 1 >= end) x + layout.getLineRight(line) else x + layout.getPrimaryHorizontal(start + m.range.last + 1)
                    word(m.value, min(left, right), top, max(left, right), bottom)
                }
            }
        }

        fun word(text: String, left: Float, top: Float, right: Float, bottom: Float) {
            words += OcrWord(text, (left / w).coerceIn(0f, 1f), (top / h).coerceIn(0f, 1f), (right / w).coerceIn(0f, 1f), (bottom / h).coerceIn(0f, 1f), confidence = 100f)
        }

        fun line(text: String) {
            if (text.isNotBlank()) lines += text
        }

        fun finish() {
            val b = bitmap ?: return
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            b.recycle()
            bitmap = null
            canvas = null
            out += ImportedPage(file, OcrResult(lines.joinToString("\n"), words.toList()).takeIf { words.isNotEmpty() })
            words.clear()
            lines.clear()
        }

        fun done(): List<ImportedPage> {
            if (bitmap == null && out.isEmpty()) start()
            finish()
            return out
        }
    }

    // ---- Word ----

    private fun word(doc: WordDocument, pages: Pages) {
        val s = pages.scale
        val left = doc.margins.left * s
        val top = doc.margins.top * s
        val bottom = pages.h - doc.margins.bottom * s
        val width = max(1f, pages.w - (doc.margins.left + doc.margins.right) * s)
        var y = top
        pages.start()
        fun newPage() {
            pages.start()
            y = top
        }
        for (block in doc.blocks) {
            when (block) {
                is OfficePageBreak -> newPage()
                is OfficeParagraph -> {
                    val size = paragraphSize(block, doc.textSize)
                    val indent = (block.level * LIST_INDENT + if (block.marker != null) LIST_INDENT else 0f) * s
                    val layout = layout(block, doc.textSize, s, (width - indent).toInt().coerceAtLeast(1))
                    val before = if (block.heading > 0) size * 0.8f * s else 0f
                    if (y > top) y += before
                    var line = 0
                    while (line < layout.lineCount) {
                        // As many lines as fit; at least one on a fresh page.
                        var last = line
                        while (last < layout.lineCount && y + layout.getLineBottom(last) - layout.getLineTop(line) <= bottom) last++
                        if (last == line) {
                            if (y > top) {
                                newPage()
                                continue
                            }
                            last = line + 1
                        }
                        val c = pages.current()
                        c.save()
                        c.translate(left + indent, y - layout.getLineTop(line))
                        c.clipRect(0f, layout.getLineTop(line).toFloat(), layout.width.toFloat(), layout.getLineBottom(last - 1).toFloat())
                        layout.draw(c)
                        c.restore()
                        if (line == 0 && block.marker != null) {
                            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size * s; color = Color.BLACK }
                            c.drawText(block.marker!!, left + block.level * LIST_INDENT * s, y + layout.getLineBaseline(0) - layout.getLineTop(0), paint)
                        }
                        pages.words(layout, line, last, left + indent, y)
                        y += layout.getLineBottom(last - 1) - layout.getLineTop(line)
                        line = last
                        if (line < layout.lineCount) newPage()
                    }
                    y += doc.textSize * PARAGRAPH_AFTER * s
                }
                is OfficeTable -> y = table(block, doc.textSize, pages, left, width, y, top, bottom) { newPage(); top }
                is OfficePicture -> {
                    val picture = decode(block.bytes, width.toInt()) ?: continue
                    // Its own size in the document, or as it is at 96 dots an inch; never wider than the text.
                    var pw = if (block.width > 0) block.width * s else picture.width * 0.75f * s
                    var ph = if (block.height > 0) block.height * s else picture.height * 0.75f * s
                    val fit = min(1f, min(width / pw, (bottom - top) / ph))
                    pw *= fit
                    ph *= fit
                    if (y + ph > bottom && y > top) newPage()
                    pages.current().drawBitmap(picture, null, RectF(left, y, left + pw, y + ph), Paint(Paint.FILTER_BITMAP_FLAG))
                    picture.recycle()
                    y += ph + doc.textSize * PARAGRAPH_AFTER * s
                }
            }
        }
    }

    private fun paragraphSize(p: OfficeParagraph, base: Float) = when (p.heading) {
        0 -> base
        1 -> base * 1.8f
        2 -> base * 1.45f
        3 -> base * 1.25f
        else -> base * 1.1f
    }

    /** [p] laid out [width] pixels wide, [scale] pixels a point, its body text [base] points. */
    private fun layout(p: OfficeParagraph, base: Float, scale: Float, width: Int): StaticLayout {
        val size = paragraphSize(p, base)
        val text = SpannableStringBuilder()
        for (run in p.runs) {
            val start = text.length
            text.append(run.text)
            val end = text.length
            if (end == start) continue
            val style = (if (run.bold || p.heading > 0) Typeface.BOLD else 0) or (if (run.italic) Typeface.ITALIC else 0)
            if (style != 0) text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (run.underline) text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // A heading's own size beats the run's when the run doesn't say.
            run.size?.takeIf { p.heading == 0 || it > size }?.let { text.setSpan(AbsoluteSizeSpan((it * scale).roundToInt()), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            run.color?.let { text.setSpan(ForegroundColorSpan(Color.rgb(it shr 16 and 255, it shr 8 and 255, it and 255)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        if (text.isEmpty()) text.append(" ")
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * scale
            color = Color.BLACK
        }
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (p.align) {
                    OfficeAlign.Center -> Layout.Alignment.ALIGN_CENTER
                    OfficeAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setLineSpacing(0f, LINE_SPACING)
            .setIncludePad(false)
        if (p.align == OfficeAlign.Justify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        return builder.build()
    }

    /** Draws [t] from [y] down, a new page whenever a row doesn't fit; returns where it ends. */
    private fun table(t: OfficeTable, base: Float, pages: Pages, left: Float, width: Float, from: Float, top: Float, bottom: Float, newPage: () -> Float): Float {
        val s = pages.scale
        val columns = max(t.widths.size, t.rows.maxOfOrNull { r -> r.sumOf { it.span } } ?: 0)
        if (columns == 0) return from
        val given = t.widths.map { it * s }.takeIf { it.size == columns && it.sum() > 0 }
        val widths = (given ?: List(columns) { width / columns }).let { ws -> ws.sum().let { total -> if (total > width) ws.map { it * width / total } else ws } }
        val pad = CELL_PAD * s
        val border = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = max(1f, s * 0.6f); style = Paint.Style.STROKE }
        var y = from
        for (row in t.rows) {
            var col = 0
            val cells = row.map { cell ->
                val x = left + widths.take(col).sum()
                val cw = widths.drop(col).take(cell.span).sum()
                col += cell.span
                val layouts = cell.paragraphs.map { layout(it, base, s, (cw - 2 * pad).toInt().coerceAtLeast(1)) }
                Triple(x, cw, layouts)
            }
            val rowHeight = cells.maxOf { (_, _, ls) -> ls.sumOf { it.height } + 2 * pad }.coerceAtLeast(base * s + 2 * pad)
            if (y + rowHeight > bottom && y > top) y = newPage()
            val c = pages.current()
            for ((x, cw, layouts) in cells) {
                c.drawRect(x, y, x + cw, y + rowHeight, border)
                var ty = y + pad
                for (l in layouts) {
                    c.save()
                    c.translate(x + pad, ty)
                    l.draw(c)
                    c.restore()
                    pages.words(l, 0, l.lineCount, x + pad, ty)
                    ty += l.height
                }
            }
            y += rowHeight
        }
        return y + base * PARAGRAPH_AFTER * s
    }

    /** A picture's bytes, decoded no bigger than needed for [width] pixels; null if they aren't one. */
    private fun decode(bytes: ByteArray, width: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- PowerPoint ----

    private fun slides(deck: Presentation, dir: File): List<ImportedPage> {
        val pages = Pages(dir, deck.width, deck.height)
        val s = pages.scale
        for (slide in deck.slides) {
            val bg = slide.background?.let { Color.rgb(it shr 16 and 255, it shr 8 and 255, it and 255) } ?: Color.WHITE
            val c = pages.start(bg)
            for (shape in slide.shapes) {
                val box = RectF(shape.x * s, shape.y * s, (shape.x + shape.width) * s, (shape.y + shape.height) * s)
                when (shape) {
                    is SlidePicture -> decode(shape.bytes, box.width().toInt().coerceAtLeast(1))?.let {
                        c.drawBitmap(it, null, box, Paint(Paint.FILTER_BITMAP_FLAG))
                        it.recycle()
                    }
                    is SlideText -> {
                        shape.fill?.let { f -> c.drawRect(box, Paint().apply { color = Color.rgb(f shr 16 and 255, f shr 8 and 255, f and 255) }) }
                        val inset = TEXT_INSET * s
                        val inner = (box.width() - 2 * inset).toInt().coerceAtLeast(1)
                        // Text that doesn't fit its box is shrunk, as PowerPoint's autofit does.
                        var shrink = 1f
                        var layouts: List<Pair<StaticLayout, OfficeParagraph>>
                        while (true) {
                            layouts = shape.paragraphs.map { p ->
                                val shrunk = p.copy(runs = p.runs.map { r -> r.copy(size = (r.size ?: TEXT_SIZE) * shrink) })
                                layout(shrunk.copy(heading = 0), TEXT_SIZE * shrink, s, (inner - p.level * LIST_INDENT * s - if (p.marker != null) LIST_INDENT * s else 0f).toInt().coerceAtLeast(1)) to p
                            }
                            val total = layouts.sumOf { it.first.height }
                            if (total <= box.height() - 2 * inset || shrink <= MIN_SHRINK) break
                            shrink *= 0.9f
                        }
                        val total = layouts.sumOf { it.first.height }
                        var y = when (shape.anchor) {
                            0 -> box.top + (box.height() - total) / 2
                            1 -> box.bottom - inset - total
                            else -> box.top + inset
                        }
                        for ((l, p) in layouts) {
                            val x = box.left + inset + p.level * LIST_INDENT * s + if (p.marker != null) LIST_INDENT * s else 0f
                            c.save()
                            c.translate(x, y)
                            l.draw(c)
                            c.restore()
                            p.marker?.let { m ->
                                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (p.runs.firstOrNull()?.size ?: TEXT_SIZE) * shrink * s; color = Color.BLACK }
                                c.drawText(m, x - LIST_INDENT * s, y + l.getLineBaseline(0), paint)
                            }
                            pages.words(l, 0, l.lineCount, x, y)
                            y += l.height
                        }
                    }
                    is SlideTable -> {
                        val rows = shape.rows.size.coerceAtLeast(1)
                        val cols = shape.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
                        val cw = box.width() / cols
                        val rh = box.height() / rows
                        val border = Paint().apply { color = Color.rgb(120, 120, 120); style = Paint.Style.STROKE; strokeWidth = max(1f, s) }
                        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(TABLE_TEXT * s, rh * 0.6f); color = Color.BLACK }
                        shape.rows.forEachIndexed { r, row ->
                            pages.line(row.joinToString("  "))
                            row.forEachIndexed { k, text ->
                                val cell = RectF(box.left + k * cw, box.top + r * rh, box.left + (k + 1) * cw, box.top + (r + 1) * rh)
                                c.drawRect(cell, border)
                                cellText(c, pages, text, cell, paint)
                            }
                        }
                    }
                }
            }
        }
        return pages.done()
    }

    /** [text] on one line in [cell], cut short to fit, its words placed. */
    private fun cellText(c: Canvas, pages: Pages, text: String, cell: RectF, paint: TextPaint) {
        if (text.isBlank()) return
        val pad = paint.textSize * 0.3f
        val room = cell.width() - 2 * pad
        val shown = if (paint.measureText(text) <= room) text else text.take(max(0, paint.breakText(text, true, room - paint.measureText("…"), null))) + "…"
        val baseline = cell.centerY() - (paint.ascent() + paint.descent()) / 2
        c.save()
        c.clipRect(cell)
        c.drawText(shown, cell.left + pad, baseline, paint)
        c.restore()
        for (m in WORD.findAll(shown)) {
            val l = cell.left + pad + paint.measureText(shown, 0, m.range.first)
            val r = cell.left + pad + paint.measureText(shown, 0, m.range.last + 1)
            pages.word(m.value.removeSuffix("…"), l, baseline + paint.ascent(), r, baseline + paint.descent())
        }
    }

    // ---- Excel ----

    private fun sheet(sheet: Sheet, dir: File): List<ImportedPage> {
        if (sheet.rows.isEmpty()) return emptyList()
        // Columns at their own widths: a character of Excel's default font is about 5.6 points.
        val widths = sheet.widths.map { it * CHAR_POINTS }
        val total = widths.sum()
        val landscape = total > A4_SHORT - 2 * SHEET_MARGIN
        val pageW = if (landscape) A4_LONG else A4_SHORT
        val pageH = if (landscape) A4_SHORT else A4_LONG
        val room = pageW - 2 * SHEET_MARGIN
        // Shrunk to fit across, down to a point; past that, the columns go onto pages of their own.
        val fit = min(1f, room / total).coerceAtLeast(MIN_SHEET_SCALE)
        val groups = ArrayList<IntRange>()
        var start = 0
        while (start < widths.size) {
            var end = start
            var used = 0f
            while (end < widths.size && (used + widths[end] * fit <= room || end == start)) used += widths[end++] * fit
            groups += start until end
            start = end
        }
        val pages = Pages(dir, pageW, pageH)
        val s = pages.scale
        val rowH = ROW_POINTS * fit * s
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SHEET_TEXT * fit * s; color = Color.BLACK }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SHEET_TITLE * s; color = Color.BLACK; typeface = Typeface.DEFAULT_BOLD }
        val grid = Paint().apply { color = Color.rgb(200, 200, 200); style = Paint.Style.STROKE; strokeWidth = max(1f, s * 0.5f) }
        val top = SHEET_MARGIN * s
        val bottom = (pageH - SHEET_MARGIN) * s
        val perPage = ((bottom - top - SHEET_TITLE * 2 * s) / rowH).toInt().coerceAtLeast(1)
        for (group in groups) {
            var row = 0
            while (row < sheet.rows.size) {
                val c = pages.start()
                c.drawText(sheet.name, SHEET_MARGIN * s, top + SHEET_TITLE * s, titlePaint)
                pages.word(sheet.name, SHEET_MARGIN * s, top, SHEET_MARGIN * s + titlePaint.measureText(sheet.name), top + SHEET_TITLE * 1.3f * s)
                pages.line(sheet.name)
                var y = top + SHEET_TITLE * 2 * s
                val last = min(sheet.rows.size, row + perPage)
                for (r in row until last) {
                    var x = SHEET_MARGIN * s
                    val cells = sheet.rows[r]
                    pages.line(group.mapNotNull { cells.getOrNull(it)?.takeIf(String::isNotBlank) }.joinToString("  "))
                    for (k in group) {
                        val cw = widths[k] * fit * s
                        if (cw <= 0f) continue
                        val cell = RectF(x, y, x + cw, y + rowH)
                        c.drawRect(cell, grid)
                        cellText(c, pages, cells.getOrNull(k).orEmpty(), cell, paint)
                        x += cw
                    }
                    y += rowH
                }
                row = last
            }
        }
        return pages.done()
    }

    private val WORD = Regex("\\S+")
    private const val DPI = 200f
    private const val MAX_SIDE = 3300f
    private const val QUALITY = 92
    private const val LIST_INDENT = 18f
    private const val PARAGRAPH_AFTER = 0.6f
    private const val LINE_SPACING = 1.12f
    private const val CELL_PAD = 4f
    private const val TEXT_SIZE = 18f
    private const val TEXT_INSET = 7.2f
    private const val MIN_SHRINK = 0.4f
    private const val TABLE_TEXT = 14f
    private const val CHAR_POINTS = 5.6f
    private const val A4_SHORT = 595f
    private const val A4_LONG = 842f
    private const val SHEET_MARGIN = 36f
    private const val SHEET_TEXT = 9f
    private const val SHEET_TITLE = 12f
    private const val ROW_POINTS = 15f
    private const val MIN_SHEET_SCALE = 0.6f
}
