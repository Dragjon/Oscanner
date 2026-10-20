package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageFilter
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
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
        val flat = flatten(rgb)
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
        val flat = flatten(rgb)
        applyLut(flat, levels(black = 40, white = 245, gamma = 1.15))
        return sharpen(flat, amount = 0.6)
    }

    /** Like Auto, but lighter midtones and softer color: good for faint pencil or photos. */
    private fun bright(rgb: Mat): Mat {
        val flat = flatten(rgb)
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
        val flat = flatten(toGray(rgb))
        applyLut(flat, levels(black = 35, white = 240, gamma = 1.05))
        return sharpen(flat, amount = 0.5)
    }

    /** Pure black on white: flattened luminance, Sauvola threshold, then despeckle. */
    private fun ink(rgb: Mat): Mat {
        val flat = flatten(toGray(rgb))
        val longSide = max(flat.cols(), flat.rows())
        val window = (longSide / 60).coerceAtLeast(15) or 1
        val binary = sauvola(flat, window, k = 0.2, range = 128.0)
        flat.release()
        Imgproc.medianBlur(binary, binary, 3)
        return binary
    }

    private fun toGray(rgb: Mat): Mat = Mat().also { Imgproc.cvtColor(rgb, it, Imgproc.COLOR_RGB2GRAY) }

    /** Divides the image by its blank-paper estimate so paper becomes white and shadows drop out. */
    private fun flatten(src: Mat): Mat {
        val background = background(src)
        val out = Mat()
        Core.divide(src, background, out, 255.0)
        background.release()
        if (src.channels() == 1) src.release()
        return out
    }

    /** The page's blank-paper estimate, also what clean-up fills removed areas with. */
    fun backgroundOf(src: Mat): Mat = background(src)

    /**
     * Downscale, morphological close (dark text is smaller than the kernel, so it's filled with
     * the surrounding paper), median to smooth, upscale.
     */
    private fun background(src: Mat): Mat {
        val scale = min(1.0, BACKGROUND_SIDE / max(src.cols(), src.rows()))
        val small = Mat()
        Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.medianBlur(small, small, 9)
        val full = Mat()
        Imgproc.resize(small, full, src.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        kernel.release()
        small.release()
        return full
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
