package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageFilter
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The five looks from PLAN.md §5.4, all deterministic. Input is 8-bit RGB; output is 8-bit RGB
 * or single-channel gray. Shadows go away because every look except Original first divides
 * the page by an estimate of its own blank paper.
 */
internal object Filters {

    /** Side length the paper background is estimated at; text strokes vanish at this size. */
    private const val BACKGROUND_SIDE = 400.0
    /** The estimate this far below the wider paper around it is a shadow or a solid shape... */
    private const val SUNK = 0.88
    /** ...a shape when this share of its edge steps by [EDGE_STEP] of the paper's brightness in a pixel or two... */
    private const val SHARP_SHARE = 0.15
    private const val EDGE_STEP = 0.25
    /** ...and it's this much more saturated than the paper around it, or darker than this share of it. */
    private const val SHAPE_TINT = 35.0
    private const val SHAPE_DARK = 0.5
    /** Ink: darker than this on flattened paper (255) is black whatever is around it. */
    private const val INK_SOLID = 150.0
    /** Smaller than this (pixels at the estimate's size) and the closing already took care of it. */
    private const val MIN_SHAPE = 20.0
    /** Grain below this many 8-bit levels is left alone: a clean photo stays untouched. */
    private const val NOISY = 2.0
    private const val NOISE_PATCH = 1024

    /**
     * Slider adjustments after the look. Ink is pure black and white, so only its threshold
     * would move; it ignores them.
     */
    fun adjust(src: Mat, filter: PageFilter, brightness: Float, contrast: Float, detail: Float): Mat {
        if (filter == PageFilter.Ink || (brightness == 0f && contrast == 0f && detail == 0f)) return src
        // contrast −1…1 maps to a gain of 0.5…2, pivoting on mid-gray; brightness shifts up to ±64.
        val gain = if (contrast >= 0f) 1.0 + contrast else 1.0 + contrast * 0.5
        val offset = 128.0 * (1.0 - gain) + brightness * 64.0
        val out = Mat()
        src.convertTo(out, -1, gain, offset)
        src.release()
        return if (detail > 0f) sharpen(out, amount = detail * 1.5) else out
    }

    fun apply(rgb: Mat, filter: PageFilter): Mat = when (filter) {
        PageFilter.Original -> rgb.clone()
        PageFilter.Auto -> auto(rgb)
        PageFilter.Bright -> bright(rgb)
        PageFilter.Gray -> gray(rgb)
        PageFilter.Ink -> ink(rgb)
        PageFilter.Board -> board(rgb)
    }

    /** Whiteboards: white board, marker colors made vivid, glare and shadows flattened. */
    private fun board(rgb: Mat): Mat {
        val flat = cleanFlatColor(rgb)
        applyLut(flat, levels(black = 70, white = 225, gamma = 1.3))
        val hsv = Mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)
        channels[1].convertTo(channels[1], -1, 1.6, 0.0)
        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, flat, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        channels.forEach(Mat::release)
        return sharpen(flat, amount = 0.5)
    }

    /** Flattened lighting, white paper, crisper text, colors kept. */
    private fun auto(rgb: Mat): Mat {
        val flat = cleanFlatColor(rgb)
        applyLut(flat, levels(black = 40, white = 245, gamma = 1.15))
        return sharpen(flat, amount = 0.6)
    }

    /** Like Auto, but lighter midtones and softer color: good for faint pencil or photos. */
    private fun bright(rgb: Mat): Mat {
        val flat = cleanFlatColor(rgb)
        applyLut(flat, levels(black = 25, white = 235, gamma = 0.85))
        val hsv = Mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = mutableListOf<Mat>()
        Core.split(hsv, channels)
        channels[1].convertTo(channels[1], -1, 0.8, 0.0)
        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, flat, Imgproc.COLOR_HSV2RGB)
        hsv.release()
        channels.forEach(Mat::release)
        return sharpen(flat, amount = 0.4)
    }

    /** Luminance only, flattened and stretched. Smallest files. */
    private fun gray(rgb: Mat): Mat {
        val flat = flatten(cleanNoise(toGray(rgb)), rgb)
        applyLut(flat, levels(black = 35, white = 240, gamma = 1.05))
        return sharpen(flat, amount = 0.5)
    }

    /** Pure black on white: flattened luminance, Sauvola threshold, then despeckle. */
    private fun ink(rgb: Mat): Mat {
        val flat = flatten(cleanNoise(toGray(rgb)), rgb)
        val longSide = max(flat.cols(), flat.rows())
        val window = (longSide / 60).coerceAtLeast(15) or 1
        val binary = sauvola(flat, window, k = 0.2, range = 128.0)
        // Sauvola compares a pixel with its neighbourhood, so the middle of a solid shape is as
        // light as its surroundings and turns white. What's clearly dark against the paper is ink.
        val dark = Mat()
        Core.compare(flat, Scalar(INK_SOLID), dark, Core.CMP_LT)
        binary.setTo(Scalar(0.0), dark)
        dark.release()
        flat.release()
        Imgproc.medianBlur(binary, binary, 3)
        return binary
    }

    private fun toGray(rgb: Mat): Mat = Mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2GRAY) }

    /**
     * Divides the image by its blank-paper estimate so paper becomes white and shadows drop out.
     * A gray [src] is a temporary and is released; a color one belongs to the caller. [colour] is
     * the page in color, which tells solid shapes from shadows (see [keepShapesOut]).
     */
    private fun flatten(src: Mat, colour: Mat = src): Mat {
        val background = background(src, colour)
        val out = Mat()
        Core.divide(src, background, out, 255.0)
        background.release()
        if (src.channels() == 1) src.release()
        return out
    }

    /** The color looks' start: grain out, lighting flattened, paper neutral. Leaves [rgb] alone. */
    private fun cleanFlatColor(rgb: Mat): Mat {
        val clean = cleanNoise(rgb)
        val flat = flatten(clean)
        if (clean !== rgb) clean.release()
        return balancePaper(flat)
    }

    /**
     * Smooths sensor grain from dim photos before the page is flattened: flattening brightens
     * shadows, and the grain with them, and grain also fools the paper estimate. Clean photos are
     * returned as they are. Brightness is smoothed only between similar tones (a bilateral
     * filter), so letter edges stay sharp; color noise, which the eye forgives in detail but not
     * in blotches, is blurred more. A gray [src] is a temporary and is released when replaced;
     * a color one belongs to the caller.
     */
    private fun cleanNoise(src: Mat): Mat {
        val sigma = noiseLevel(src)
        if (sigma < NOISY) return src
        val out = Mat()
        if (src.channels() == 1) {
            Imgproc.bilateralFilter(src, out, 7, sigma * 3, 3.0)
            src.release()
            return out
        }
        val ycc = Mat()
        Imgproc.cvtColor(src, ycc, Imgproc.COLOR_RGB2YCrCb)
        val channels = mutableListOf<Mat>()
        Core.split(ycc, channels)
        val luma = Mat()
        Imgproc.bilateralFilter(channels[0], luma, 7, sigma * 3, 3.0)
        channels[0].release()
        channels[0] = luma
        val chromaSigma = max(1.5, max(src.cols(), src.rows()) / 1200.0)
        Imgproc.GaussianBlur(channels[1], channels[1], Size(0.0, 0.0), chromaSigma)
        Imgproc.GaussianBlur(channels[2], channels[2], Size(0.0, 0.0), chromaSigma)
        Core.merge(channels, ycc)
        channels.forEach(Mat::release)
        Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
        ycc.release()
        return out
    }

    /**
     * Standard deviation of the grain, in 8-bit levels: Immerkær's fast estimate ("Fast Noise
     * Variance Estimation", 1996), taken on the flatter 60% of a central patch so that text
     * edges don't count as noise.
     */
    private fun noiseLevel(src: Mat): Double {
        val side = min(NOISE_PATCH, min(src.cols(), src.rows()))
        val patch = src.submat(
            (src.rows() - side) / 2, (src.rows() + side) / 2,
            (src.cols() - side) / 2, (src.cols() + side) / 2,
        )
        val gray = Mat()
        if (patch.channels() == 1) patch.copyTo(gray) else Imgproc.cvtColor(patch, gray, Imgproc.COLOR_RGB2GRAY)
        patch.release()
        val kernel = Mat(3, 3, CvType.CV_32F).apply { put(0, 0, floatArrayOf(1f, -2f, 1f, -2f, 4f, -2f, 1f, -2f, 1f)) }
        val response = Mat()
        Imgproc.filter2D(gray, response, CvType.CV_32F, kernel)
        Core.absdiff(response, org.opencv.core.Scalar.all(0.0), response)
        kernel.release()
        val edges = Mat()
        Imgproc.Sobel(gray, edges, CvType.CV_8U, 1, 1, 3, 1.0, 0.0)
        gray.release()
        val flat = Mat()
        Core.compare(edges, org.opencv.core.Scalar.all(percentile(edges, 0.6).toDouble()), flat, Core.CMP_LE)
        edges.release()
        val mean = Core.mean(response, flat).`val`[0]
        response.release()
        flat.release()
        return kotlin.math.sqrt(Math.PI / 2) * mean / 6
    }

    /** The value below which [fraction] of an 8-bit image's pixels fall. */
    private fun percentile(gray: Mat, fraction: Double): Int {
        val bytes = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, bytes)
        val counts = IntArray(256)
        bytes.forEach { counts[it.toInt() and 0xFF]++ }
        val target = bytes.size * fraction
        var seen = 0
        counts.forEachIndexed { value, n ->
            seen += n
            if (seen >= target) return value
        }
        return 255
    }

    /**
     * After flattening, scales each channel so the paper (the brighter half of the page) is
     * neutral. Flattening already undoes most of a lamp's tint; this takes out what's left.
     */
    private fun balancePaper(rgb: Mat): Mat {
        if (rgb.channels() != 3) return rgb
        val small = Mat()
        val scale = min(1.0, 400.0 / max(rgb.cols(), rgb.rows()))
        Imgproc.resize(rgb, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        val paper = Mat()
        Core.compare(gray, org.opencv.core.Scalar.all(percentile(gray, 0.5).toDouble()), paper, Core.CMP_GE)
        gray.release()
        val mean = Core.mean(small, paper).`val`
        small.release()
        paper.release()
        val top = maxOf(mean[0], mean[1], mean[2])
        if (top <= 0.0) return rgb
        val gains = org.opencv.core.Scalar(top / max(mean[0], 1.0), top / max(mean[1], 1.0), top / max(mean[2], 1.0))
        Core.multiply(rgb, gains, rgb)
        return rgb
    }

    /** The page's blank-paper estimate, also what clean-up fills removed areas with. */
    fun backgroundOf(src: Mat): Mat = background(src)

    /**
     * Downscale, morphological close (dark text is smaller than the kernel, so it's filled with
     * the surrounding paper), median to smooth, upscale.
     */
    private fun background(src: Mat, colour: Mat? = null): Mat {
        val scale = min(1.0, BACKGROUND_SIDE / max(src.cols(), src.rows()))
        val small = Mat()
        Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.medianBlur(small, small, 9)
        if (colour != null && colour.channels() == 3) keepShapesOut(small, colour)
        val full = Mat()
        Imgproc.resize(small, full, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        kernel.release()
        small.release()
        return full
    }

    /**
     * A solid shape bigger than the closing (a chart's bar, a black box, a photo) pulls the paper
     * estimate [paper] down inside it, and dividing by that turns the shape white with only its
     * outline left. A shadow does the same, and should. They're told apart by their edges and
     * their color: a shape stops sharply and is colored or very dark; a shadow fades, or is only
     * a little darker. Over shapes, the estimate is drawn in from the paper around them.
     */
    private fun keepShapesOut(paper: Mat, colour: Mat) {
        val w = paper.cols()
        val h = paper.rows()
        val rgb = Mat()
        Imgproc.resize(colour, rgb, paper.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = toGray(rgb)
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        rgb.release()
        val saturation = Mat()
        Core.extractChannel(hsv, saturation, 1)
        hsv.release()
        val estimate = if (paper.channels() == 1) paper.clone() else toGray(paper)
        // The paper further out: closed far wider than any shape that matters.
        val reach = (max(w, h) / 5).coerceAtLeast(31) or 1
        val wide = Mat()
        Imgproc.morphologyEx(estimate, wide, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(reach.toDouble(), reach.toDouble())))
        // Where the estimate sank clearly below that: a shadow or a shape.
        val lifted = Mat()
        Core.multiply(estimate, Scalar(1.0 / SUNK), lifted)
        val sunk = Mat()
        Core.compare(lifted, wide, sunk, Core.CMP_LT)
        listOf(lifted, estimate).forEach(Mat::release)
        val edges = Mat()
        Imgproc.morphologyEx(gray, edges, Imgproc.MORPH_GRADIENT, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(sunk, labels, stats, centroids, 8, CvType.CV_32S)
        val holes = Mat.zeros(h, w, CvType.CV_8UC1)
        val part = Mat()
        val grown = Mat()
        val shrunk = Mat()
        val band = Mat()
        val sharp = Mat()
        val near = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        for (k in 1 until count) {
            if (stats.get(k, Imgproc.CC_STAT_AREA)[0] < MIN_SHAPE) continue
            // At the image's edge it's desk beside an uncropped page, or a shadow coming in.
            val x = stats.get(k, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(k, Imgproc.CC_STAT_TOP)[0].toInt()
            if (x <= 0 || y <= 0 || x + stats.get(k, Imgproc.CC_STAT_WIDTH)[0].toInt() >= w || y + stats.get(k, Imgproc.CC_STAT_HEIGHT)[0].toInt() >= h) continue
            Core.compare(labels, Scalar(k.toDouble()), part, Core.CMP_EQ)
            Imgproc.dilate(part, grown, near)
            Imgproc.erode(part, shrunk, near)
            Core.subtract(grown, shrunk, band)
            val level = Core.mean(wide, part).`val`[0].coerceAtLeast(1.0)
            // Sharp: a good share of the edge band steps by a quarter of the paper's brightness.
            Core.compare(edges, Scalar(EDGE_STEP * level), sharp, Core.CMP_GT)
            Core.bitwise_and(sharp, band, sharp)
            val sharpShare = Core.countNonZero(sharp).toDouble() / Core.countNonZero(band).coerceAtLeast(1)
            if (sharpShare < SHARP_SHARE) continue
            val darkness = Core.mean(gray, part).`val`[0] / level
            // The ring just outside is the paper the shape sits on.
            Core.subtract(grown, part, band)
            val tint = Core.mean(saturation, part).`val`[0] - Core.mean(saturation, band).`val`[0]
            if (tint > SHAPE_TINT || darkness < SHAPE_DARK) Core.bitwise_or(holes, grown, holes)
        }
        listOf(labels, stats, centroids, part, grown, shrunk, band, sharp, near, sunk, edges, wide, gray, saturation).forEach(Mat::release)
        if (Core.countNonZero(holes) > 0) Photo.inpaint(paper, holes, paper, 5.0, Photo.INPAINT_TELEA)
        holes.release()
    }

    /**
     * Sauvola (2000): threshold = mean · (1 + k · (std / R − 1)) over a [window]-sized box.
     * Returns 255 for paper and 0 for ink.
     */
    private fun sauvola(gray: Mat, window: Int, k: Double, range: Double): Mat {
        val size = Size(window.toDouble(), window.toDouble())
        val f = Mat()
        gray.convertTo(f, CvType.CV_32F)
        val mean = Mat()
        Imgproc.boxFilter(f, mean, -1, size)
        val sq = f.mul(f)
        val meanSq = Mat()
        Imgproc.boxFilter(sq, meanSq, -1, size)
        sq.release()
        val meanOfSquare = mean.mul(mean)
        val variance = Mat()
        Core.subtract(meanSq, meanOfSquare, variance)
        meanSq.release()
        meanOfSquare.release()
        Imgproc.threshold(variance, variance, 0.0, 0.0, Imgproc.THRESH_TOZERO)
        val std = Mat()
        Core.sqrt(variance, std)
        variance.release()
        // factor = 1 - k + (k / R) · std
        std.convertTo(std, -1, k / range, 1.0 - k)
        val threshold = mean.mul(std)
        mean.release()
        std.release()
        val out = Mat()
        Core.compare(f, threshold, out, Core.CMP_GT)
        f.release()
        threshold.release()
        return out
    }

    private fun sharpen(src: Mat, amount: Double): Mat {
        val sigma = max(1.0, max(src.cols(), src.rows()) / 1500.0)
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), sigma)
        val out = Mat()
        Core.addWeighted(src, 1.0 + amount, blurred, -amount, 0.0, out)
        blurred.release()
        src.release()
        return out
    }

    private fun levels(black: Int, white: Int, gamma: Double): Mat {
        val table = ByteArray(256) { i ->
            val t = ((i - black).toDouble() / (white - black)).coerceIn(0.0, 1.0)
            (t.pow(gamma) * 255.0 + 0.5).toInt().toByte()
        }
        return Mat(1, 256, CvType.CV_8U).apply { put(0, 0, table) }
    }

    private fun applyLut(mat: Mat, lut: Mat) {
        Core.LUT(mat, lut, mat)
        lut.release()
    }
}
