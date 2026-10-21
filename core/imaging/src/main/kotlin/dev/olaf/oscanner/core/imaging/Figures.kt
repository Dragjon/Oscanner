package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the pictures on a finished page (photos, logos, charts, drawings) for exports that
 * rebuild its layout: marks and colour that no read word explains, gathered into solid areas.
 * Ruled boxes and table borders are mostly empty inside, so they don't count.
 */
object Figures {

    /** Picture areas on [page], normalized, top to bottom. [words] are the read words' boxes, normalized. */
    fun find(page: Bitmap, words: List<RectF>): List<RectF> {
        PageRenderer.ensureLoaded()
        val scale = WORK_SIDE.toDouble() / max(page.width, page.height)
        val rgba = Mat()
        Utils.bitmapToMat(page, rgba)
        val small = Mat()
        Imgproc.resize(rgba, small, Size(), scale, scale, Imgproc.INTER_AREA)
        rgba.release()
        val w = small.cols()
        val h = small.rows()

        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
        val rgb = Mat()
        Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val saturation = Mat()
        Core.extractChannel(hsv, saturation, 1)

        // Ink: anything darker than paper, or clearly coloured.
        val ink = Mat()
        Imgproc.threshold(gray, ink, INK_LEVEL, 255.0, Imgproc.THRESH_BINARY_INV)
        val colour = Mat()
        Imgproc.threshold(saturation, colour, COLOUR_LEVEL, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(ink, colour, ink)
        // Tint: any shade off the paper, however pale; a chart's or photo's panel is often only that.
        val paperMask = Mat()
        Core.compare(gray, Scalar(INK_LEVEL), paperMask, Core.CMP_GE)
        val paperColour = Core.mean(rgb, paperMask)
        paperMask.release()
        val tint = Mat()
        Core.absdiff(rgb, paperColour, tint)
        val spread = Mat()
        Imgproc.cvtColor(tint, spread, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(spread, tint, TINT_LEVEL, 255.0, Imgproc.THRESH_BINARY)
        spread.release()
        listOf(small, gray, rgb, hsv, saturation, colour).forEach(Mat::release)

        // Words are text, not pictures: blanked out, with a little room around each.
        for (r in words) {
            val pad = (r.bottom - r.top) * 0.3f
            Imgproc.rectangle(
                ink,
                Point(((r.left - pad) * w).toDouble(), ((r.top - pad) * h).toDouble()),
                Point(((r.right + pad) * w).toDouble(), ((r.bottom + pad) * h).toDouble()),
                Scalar(0.0),
                -1,
            )
        }
        val solid = ink.clone()
        for (r in words) {
            val pad = (r.bottom - r.top) * 0.3f
            Imgproc.rectangle(
                tint,
                Point(((r.left - pad) * w).toDouble(), ((r.top - pad) * h).toDouble()),
                Point(((r.right + pad) * w).toDouble(), ((r.bottom + pad) * h).toDouble()),
                Scalar(0.0),
                -1,
            )
        }
        val panels = Mat()
        val panelStats = Mat()
        val panelCentroids = Mat()
        val panelCount = Imgproc.connectedComponentsWithStats(tint, panels, panelStats, panelCentroids)
        listOf(tint, panels, panelCentroids).forEach(Mat::release)
        Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(GATHER, GATHER)))

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids)
        val found = mutableListOf<RectF>()
        for (i in 1 until count) {
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (bw < MIN_SIDE * w || bh < MIN_SIDE * h || bw.toDouble() * bh < MIN_AREA * w * h) continue
            // Touching both sides or top and bottom: a shadow or the page's own edge, not a picture.
            val touches = listOf(x <= 1, y <= 1, x + bw >= w - 1, y + bh >= h - 1)
            if ((touches[0] && touches[2]) || (touches[1] && touches[3])) continue
            val box = solid.submat(y, y + bh, x, x + bw)
            val fill = Core.countNonZero(box).toDouble() / (bw.toDouble() * bh)
            box.release()
            if (fill < MIN_FILL) continue
            // Out to the panel it sits on: tint that holds the picture and isn't much bigger.
            var (x0, y0, x1, y1) = listOf(x, y, x + bw, y + bh)
            for (k in 1 until panelCount) {
                val px = panelStats.get(k, Imgproc.CC_STAT_LEFT)[0].toInt()
                val py = panelStats.get(k, Imgproc.CC_STAT_TOP)[0].toInt()
                val pw = panelStats.get(k, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val ph = panelStats.get(k, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val holds = px <= x && py <= y && px + pw >= x + bw && py + ph >= y + bh
                if (holds && pw.toDouble() * ph < PANEL_GROWTH * bw * bh && pw < w - 2 && ph < h - 2) {
                    x0 = min(x0, px); y0 = min(y0, py); x1 = max(x1, px + pw); y1 = max(y1, py + ph)
                }
            }
            val pad = PAD
            found += RectF(
                max(0f, x0.toFloat() / w - pad),
                max(0f, y0.toFloat() / h - pad),
                min(1f, x1.toFloat() / w + pad),
                min(1f, y1.toFloat() / h + pad),
            )
        }
        listOf(ink, solid, labels, stats, centroids, panelStats).forEach(Mat::release)
        return found.sortedBy { it.top }
    }

    /** The page is looked at this big on its long side. */
    private const val WORK_SIDE = 700.0
    private const val INK_LEVEL = 190.0
    private const val COLOUR_LEVEL = 70.0
    /** Marks this close together (pixels at the working size) belong to one picture: a chart's bars, say. */
    private const val GATHER = 31.0
    /** Off the paper's colour by this much (gray levels of difference): a tinted panel. */
    private const val TINT_LEVEL = 6.0
    /** A panel is taken in when it's at most this many times the picture's area. */
    private const val PANEL_GROWTH = 3.0
    /** A picture is at least this share of the page each way, and of its area. */
    private const val MIN_SIDE = 0.06
    private const val MIN_AREA = 0.012
    /** Share of a picture's box its marks fill: a ruled box or a table's grid fills far less. */
    private const val MIN_FILL = 0.18
    private const val PAD = 0.008f
}
