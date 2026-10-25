package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Takes print off a picture and leaves the picture: for writing a translation over text set on
 * a photo or a gradient, where painting the paragraph over in one color would leave a flat
 * patch. What's under the letters is told from around them, a stroke's width away (a median,
 * which a letter's thin strokes don't move); pixels that stand out from it and lie nearer the
 * ink's color than its are the print. Sharpening leaves a pale rim round dark letters (and a
 * dark one round pale letters), so a good margin round each letter goes too; all of it is
 * filled from the picture around.
 */
object PrintEraser {

    /** Print to erase: its box in pixels, its color (ARGB) and its letters' height in pixels. */
    class Print(val left: Int, val top: Int, val right: Int, val bottom: Int, val ink: Int, val size: Float)

    /** Erases each of [prints] from [page], in place. */
    fun erase(page: Bitmap, prints: List<Print>) {
        if (prints.isEmpty()) return
        PageRenderer.ensureLoaded()
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        var changed = false
        for (p in prints) {
            val ink = Scalar(((p.ink shr 16) and 255).toDouble(), ((p.ink shr 8) and 255).toDouble(), (p.ink and 255).toDouble())
            val region = erase(rgb, Rect(p.left, p.top, p.right - p.left, p.bottom - p.top), ink, p.size.toDouble()) ?: continue
            val back = Mat()
            Imgproc.cvtColor(rgb.submat(region), back, Imgproc.COLOR_RGB2RGBA)
            back.copyTo(rgba.submat(region))
            back.release()
            changed = true
        }
        if (changed) Utils.matToBitmap(rgba, page)
        rgba.release()
        rgb.release()
    }

    /** The same on an RGB [rgb], in place; returns the part of it that was changed, or null. */
    internal fun erase(rgb: Mat, box: Rect, ink: Scalar, size: Double): Rect? {
        val reach = (size * REACH).roundToInt()
        val x0 = max(0, box.x - reach)
        val y0 = max(0, box.y - reach)
        val x1 = min(rgb.cols(), box.x + box.width + reach)
        val y1 = min(rgb.rows(), box.y + box.height + reach)
        if (x1 - x0 < 3 || y1 - y0 < 3) return null
        val area = Rect(x0, y0, x1 - x0, y1 - y0)
        val region = rgb.submat(area)
        // What's under the print: wide enough to step over a stroke, as near as that allows.
        val k = max(3, (size * UNDER).roundToInt()) or 1
        val under = Mat()
        Imgproc.medianBlur(region, under, k)
        val px = ByteArray(area.width * area.height * 3).also { region.get(0, 0, it) }
        val bg = ByteArray(px.size).also { under.get(0, 0, it) }
        under.release()
        val ir = ink.`val`[0]
        val ig = ink.`val`[1]
        val ib = ink.`val`[2]
        val inBox = Rect(box.x - x0, box.y - y0, box.width, box.height)
        val mask = Mat.zeros(area.height, area.width, CvType.CV_8UC1)
        val marks = ByteArray(area.width * area.height)
        var count = 0
        for (y in max(0, inBox.y) until min(area.height, inBox.y + inBox.height)) {
            for (x in max(0, inBox.x) until min(area.width, inBox.x + inBox.width)) {
                val i = (y * area.width + x) * 3
                val r = (px[i].toInt() and 255).toDouble()
                val g = (px[i + 1].toInt() and 255).toDouble()
                val b = (px[i + 2].toInt() and 255).toDouble()
                val br = (bg[i].toInt() and 255).toDouble()
                val bgg = (bg[i + 1].toInt() and 255).toDouble()
                val bb = (bg[i + 2].toInt() and 255).toDouble()
                val fromBg = sq(r - br) + sq(g - bgg) + sq(b - bb)
                val fromInk = sq(r - ir) + sq(g - ig) + sq(b - ib)
                if (fromBg > STANDS_OUT * STANDS_OUT && fromInk < fromBg) {
                    marks[y * area.width + x] = -1
                    count++
                }
            }
        }
        if (count == 0) {
            mask.release()
            region.release()
            return null
        }
        mask.put(0, 0, marks)
        // Over each letter's soft edge and the rim sharpening left round it.
        val grow = max(3, (size * EDGE).roundToInt()) or 1
        Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(grow.toDouble(), grow.toDouble())))
        val work = region.clone()
        PatchFill.fill(work, mask)
        work.copyTo(region)
        listOf(work, mask, region).forEach(Mat::release)
        return area
    }

    private fun sq(v: Double) = v * v

    /** Pictures are drawn from this many letter heights around the print. */
    private const val REACH = 2.0
    /** What's under the print is a median this many letter heights across. */
    private const val UNDER = 0.6
    /** Print stands out from what's under it by at least this much (RGB distance). */
    private const val STANDS_OUT = 30.0
    /** The marks are grown by this many letter heights, over the letters' soft edges and rims. */
    private const val EDGE = 0.35
}
