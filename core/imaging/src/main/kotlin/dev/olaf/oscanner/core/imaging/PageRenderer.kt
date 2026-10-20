package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.olaf.oscanner.core.model.InkMark
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import dev.olaf.oscanner.core.model.PageCurve
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.TextMark
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Turns an original photo plus its [PageEdits] into the page people see. Call off the main thread. */
object PageRenderer {

    /** Long side of a full render. Sharp enough to print a page at ~300 dpi on A5, ~240 on A4. */
    const val RENDER_SIDE = 2800
    const val THUMB_SIDE = 360
    /** Roughly 300 dpi on an A4 page: what Tesseract reads best. */
    const val OCR_SIDE = 3400
    /** Plenty to trace text lines; the detector works at 1200 wide anyway. */
    private const val MEASURE_SIDE = 2000

    @Volatile private var loaded = false

    /** Loads OpenCV's native code once. Cheap after the first call. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
                loaded = true
            }
        }
    }

    /** Decodes [path] turned upright per its EXIF tag, with the long side near [maxSide] (never under it unless the photo is). */
    fun loadUpright(path: String, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Can't decode $path")
        val degrees = runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
        val scale = min(1f, maxSide.toFloat() / max(decoded.width, decoded.height))
        if (degrees == 0 && scale == 1f) return decoded
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(degrees.toFloat())
        }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it != decoded) decoded.recycle()
        }
    }

    /** Crops, straightens, rotates and filters [source] (an upright photo). */
    fun render(source: Bitmap, edits: PageEdits, maxSide: Int = RENDER_SIDE, redactionColor: Int = android.graphics.Color.BLACK): Bitmap {
        ensureLoaded()
        val warped = warp(source, edits, maxSide)
        paperOverRedactions(warped, edits)
        Cleanup.apply(warped, edits)
        val filtered = Filters.apply(warped, edits.filter)
        warped.release()
        val adjusted = Filters.adjust(filtered, edits.filter, edits.brightness, edits.contrast, edits.detail)
        return toBitmap(adjusted).also {
            adjusted.release()
            drawMarks(it, edits.marks)
            drawTexts(Canvas(it), edits.texts, it.width.toFloat(), it.height.toFloat())
            drawRedactions(it, edits, redactionColor)
        }
    }

    /**
     * Solid black over each blacked-out area, last, so no look or slider can lighten it. The
     * photo underneath was already blacked out in [warp]; this keeps the box crisp and dark.
     */
    fun drawRedactions(page: Bitmap, edits: PageEdits, fill: Int = android.graphics.Color.BLACK) {
        if (edits.redactions.isEmpty()) return
        val canvas = Canvas(page)
        val paint = Paint().apply {
            color = fill
            // A hair wider than the area, over the soft edge the warp leaves around it.
            style = Paint.Style.FILL_AND_STROKE
            strokeJoin = Paint.Join.MITER
            strokeWidth = max(3f, page.width * 0.002f)
        }
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        for (shape in QuadGeometry.redactionsOnPage(edits)) {
            val path = Path()
            shape.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Stamps [text] once, large and diagonal across the middle, in translucent gray, so it marks
     * the copy without hiding what's under it. Android's text drawing handles every script.
     */
    fun drawWatermark(page: Bitmap, watermark: String) {
        val text = watermark.trim()
        if (text.isEmpty()) return
        val canvas = Canvas(page)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x38_6F_62_5A
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        val diagonal = kotlin.math.hypot(w, h)
        // Size the text to span about 70% of the diagonal.
        paint.textSize = 100f
        paint.textSize = (100f * diagonal * 0.7f / paint.measureText(text)).coerceAtMost(h / 6)
        val angle = Math.toDegrees(kotlin.math.atan2(h.toDouble(), w.toDouble())).toFloat()
        canvas.save()
        canvas.rotate(-angle, w / 2, h / 2)
        canvas.drawText(text, w / 2, h / 2 - (paint.ascent() + paint.descent()) / 2, paint)
        canvas.restore()
    }

    /**
     * Typed text and stamps on a [width]×[height] page drawn on [canvas]. The editor draws with
     * this too, so what's placed is exactly what's saved.
     */
    fun drawTexts(canvas: Canvas, texts: List<TextMark>, width: Float, height: Float) {
        for (mark in texts) {
            val paint = textPaint(mark, width)
            val cx = mark.x * width
            val cy = mark.y * height
            canvas.save()
            canvas.rotate(mark.angle.toFloat(), cx, cy)
            val lines = mark.text.lines()
            val lineHeight = paint.fontSpacing
            val firstBaseline = cy - lineHeight * (lines.size - 1) / 2 - (paint.ascent() + paint.descent()) / 2
            lines.forEachIndexed { i, line -> canvas.drawText(line, cx, firstBaseline + i * lineHeight, paint) }
            if (mark.stamp) {
                val half = textHalfSize(mark, width)
                val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = mark.color
                    strokeWidth = paint.textSize * 0.09f
                }
                val r = paint.textSize * 0.25f
                canvas.drawRoundRect(cx - half.first, cy - half.second, cx + half.first, cy + half.second, r, r, frame)
            }
            canvas.restore()
        }
    }

    /**
     * Half the width and height a text mark covers, in pixels of a page [width] wide, before
     * it's turned; a stamp's frame included. For hit-testing in the editor.
     */
    fun textHalfSize(mark: TextMark, width: Float): Pair<Float, Float> {
        val paint = textPaint(mark, width)
        val lines = mark.text.lines()
        val textWidth = lines.maxOf { paint.measureText(it) }
        val textHeight = paint.fontSpacing * (lines.size - 1) + (paint.descent() - paint.ascent())
        val pad = if (mark.stamp) paint.textSize * 0.4f else paint.textSize * 0.1f
        return (textWidth / 2 + pad) to (textHeight / 2 + pad)
    }

    private fun textPaint(mark: TextMark, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mark.color
        textAlign = Paint.Align.CENTER
        textSize = (mark.size * width).coerceAtLeast(4f)
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            if (mark.stamp) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
        )
        // Stamps are spaced out a little, like the rubber kind.
        if (mark.stamp) letterSpacing = 0.06f
    }

    /** Strokes on top of the finished page; highlighter multiplies so the text shows through unchanged. */
    fun drawMarks(page: Bitmap, marks: List<InkMark>) {
        if (marks.isEmpty()) return
        val canvas = Canvas(page)
        val w = page.width.toFloat()
        val h = page.height.toFloat()
        for (mark in marks) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = (mark.width * w).coerceAtLeast(1f)
                color = mark.color
                // Opaque light color, multiplied: paper takes the color, ink stays black.
                if (mark.highlighter) xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
            }
            if (mark.points.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(mark.points[0].x * w, mark.points[0].y * h, paint.strokeWidth / 2, paint)
                continue
            }
            val path = Path()
            mark.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            canvas.drawPath(path, paint)
        }
    }

    /**
     * The page as Tesseract should see it: same crop, straightening and rotation as [render], so
     * word boxes line up with the page people see, but always in clean black and white (Ink).
     */
    fun renderForOcr(source: Bitmap, edits: PageEdits, filter: PageFilter = PageFilter.Ink): Bitmap =
        // Blacked-out areas read as blank paper: big black blocks make Tesseract misjudge the
        // words beside them. What was under them is gone either way.
        render(source, edits.copy(filter = filter, marks = emptyList(), texts = emptyList()), OCR_SIDE, android.graphics.Color.WHITE)

    /**
     * What clean-up will remove, as an 8-bit alpha image the size of the straightened page
     * at [maxSide]: opaque where removed. Null when nothing will be.
     */
    fun cleanupMask(source: Bitmap, edits: PageEdits, maxSide: Int): Bitmap? {
        ensureLoaded()
        val warped = warp(source, edits, maxSide)
        val mask = Cleanup.mask(warped, edits)
        warped.release()
        mask ?: return null
        val rgba = Mat()
        // White where removed, transparent elsewhere: the screen draws it as a hatch.
        val channels = listOf(mask, mask, mask, mask)
        Core.merge(channels, rgba)
        mask.release()
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    /** Crop + straighten + rotate only; the input to [applyFilter] for filter previews. */
    fun straighten(source: Bitmap, edits: PageEdits, maxSide: Int): Bitmap {
        ensureLoaded()
        val warped = warp(source, edits, maxSide)
        return toBitmap(warped).also { warped.release() }
    }

    fun applyFilter(straightened: Bitmap, filter: PageFilter): Bitmap {
        ensureLoaded()
        val rgb = toRgb(straightened)
        val filtered = Filters.apply(rgb, filter)
        rgb.release()
        return toBitmap(filtered).also { filtered.release() }
    }

    fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 88) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun saveThumbnail(render: Bitmap, file: File) {
        val scale = min(1f, THUMB_SIDE.toFloat() / max(render.width, render.height))
        val thumb = Bitmap.createScaledBitmap(render, (render.width * scale).toInt().coerceAtLeast(1), (render.height * scale).toInt().coerceAtLeast(1), true)
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.parentFile?.mkdirs()
        file.outputStream().use { thumb.compress(format, 82, it) }
        if (thumb != render) thumb.recycle()
    }

    /**
     * Fills blacked-out areas of the straightened page with its average color, a little wider
     * than the area. The looks judge paper from its surroundings, and a big black block would
     * darken or garble the words beside it, which text reading then gets wrong.
     */
    private fun paperOverRedactions(page: Mat, edits: PageEdits) {
        if (edits.redactions.isEmpty()) return
        val paper = Core.mean(page)
        val w = page.cols().toDouble()
        val h = page.rows().toDouble()
        val outlines = QuadGeometry.redactionsOnPage(edits).map { shape -> MatOfPoint(*shape.map { Point(it.x * w, it.y * h) }.toTypedArray()) }
        Imgproc.fillPoly(page, outlines, paper)
        Imgproc.polylines(page, outlines, true, paper, max(4, (w * 0.004).toInt()))
        outlines.forEach(Mat::release)
    }

    /**
     * How text lines bend on this page as cropped: what [PageEdits.curve] holds once
     * flattening is turned on. Empty when there aren't enough clear lines to tell.
     */
    fun measureCurve(source: Bitmap, edits: PageEdits): PageCurve {
        ensureLoaded()
        val straight = warp(source, edits.copy(flatten = false, rotation = 0), MEASURE_SIDE)
        return CurveDetector.detect(straight).also { straight.release() }
    }

    private fun warp(source: Bitmap, edits: PageEdits, maxSide: Int): Mat {
        val rgb = toRgb(source)
        val w = source.width
        val h = source.height
        // Blacked-out areas leave the photo first, so nothing downstream (looks, clean-up,
        // text reading, previews) ever sees what was under them.
        for (area in edits.redactions) {
            val outline = MatOfPoint(*area.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
            Imgproc.fillPoly(rgb, listOf(outline), Scalar(0.0, 0.0, 0.0))
            outline.release()
        }
        val (outW, outH) = QuadGeometry.warpSize(edits.quad, w, h).let { (mw, mh) ->
            val aspect = edits.aspect ?: return@let mw to mh
            // Keep the longer measured side for resolution; derive the other from the aspect.
            val long = maxOf(mw, mh)
            val short = (long / aspect).toInt().coerceAtLeast(1)
            if (mw >= mh) long to short else short to long
        }
        val scale = min(1.0, maxSide.toDouble() / max(outW, outH))
        val dstW = (outW * scale).coerceAtLeast(1.0)
        val dstH = (outH * scale).coerceAtLeast(1.0)
        val src = MatOfPoint2f(*edits.quad.corners.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(dstW, 0.0), Point(dstW, dstH), Point(0.0, dstH))
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(rgb, out, transform, Size(dstW, dstH), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        listOf(rgb, src, dst, transform).forEach(Mat::release)
        val curve = edits.curve
        val flat = if (edits.flatten && curve != null && curve.lines.isNotEmpty()) {
            CurveDetector.flatten(out, curve).also { out.release() }
        } else {
            out
        }
        val warped = if (edits.descreen) Descreen.apply(flat).also { flat.release() } else flat
        val turn = when (((edits.rotation % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> null
        }
        if (turn != null) Core.rotate(warped, warped, turn)
        return warped
    }

    private fun toRgb(bitmap: Bitmap): Mat {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(argb, rgba)
        if (argb != bitmap) argb.recycle()
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    private fun toBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, if (mat.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_RGB2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }
}
