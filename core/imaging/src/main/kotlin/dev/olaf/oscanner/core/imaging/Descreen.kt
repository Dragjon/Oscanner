package dev.olaf.oscanner.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Takes out the wavy bands and fine grids that appear when a screen, or a picture printed with
 * dots, is photographed (moiré). Such patterns are regular, so in the frequency spectrum they're
 * sharp peaks standing well above their neighborhood, while text spreads its energy smoothly.
 * Brightness is processed in overlapping tiles: each tile's peaks are brought down to the level
 * around them, and the tiles are blended back with windows that sum to one. The rainbow tint the
 * pattern leaves is blurred out of the color channels.
 */
internal object Descreen {

    private const val TILE = 512
    private const val STEP = TILE / 2
    /** How far (natural log of magnitude) a peak must stand above its surroundings. */
    private const val PEAK = 2.0
    /** Below this, as a share of the sampling rate, are page-scale shapes: never touched. */
    private const val LOWEST = 1.0 / 40

    /** [rgb] is 8-bit RGB; returns a new image. */
    fun apply(rgb: Mat): Mat {
        val ycc = Mat()
        Imgproc.cvtColor(rgb, ycc, Imgproc.COLOR_RGB2YCrCb)
        val channels = mutableListOf<Mat>()
        Core.split(ycc, channels)
        val luma = notch(channels[0])
        channels[0].release()
        channels[0] = luma
        val chroma = max(4.0, max(rgb.cols(), rgb.rows()) / 250.0)
        Imgproc.GaussianBlur(channels[1], channels[1], Size(0.0, 0.0), chroma)
        Imgproc.GaussianBlur(channels[2], channels[2], Size(0.0, 0.0), chroma)
        Core.merge(channels, ycc)
        channels.forEach(Mat::release)
        val out = Mat()
        Imgproc.cvtColor(ycc, out, Imgproc.COLOR_YCrCb2RGB)
        ycc.release()
        return out
    }

    private fun notch(gray: Mat): Mat {
        val h = gray.rows()
        val w = gray.cols()
        val padded = Mat()
        gray.convertTo(padded, CvType.CV_32F)
        Core.copyMakeBorder(padded, padded, STEP, STEP + TILE, STEP, STEP + TILE, Core.BORDER_REFLECT)
        val sum = Mat.zeros(padded.size(), CvType.CV_32F)
        val window = window()
        val far = farFromCenter()
        var ty = 0
        while (ty < h + STEP) {
            var tx = 0
            while (tx < w + STEP) {
                val tile = padded.submat(ty, ty + TILE, tx, tx + TILE)
                val cleaned = notchTile(tile, far)
                tile.release()
                Core.multiply(cleaned, window, cleaned)
                val target = sum.submat(ty, ty + TILE, tx, tx + TILE)
                Core.add(target, cleaned, target)
                target.release()
                cleaned.release()
                tx += STEP
            }
            ty += STEP
        }
        padded.release()
        window.release()
        far.release()
        val inner = sum.submat(STEP, STEP + h, STEP, STEP + w)
        val out = Mat()
        inner.convertTo(out, CvType.CV_8U)
        inner.release()
        sum.release()
        return out
    }

    private fun notchTile(tile: Mat, far: Mat): Mat {
        val spectrum = Mat()
        Core.dft(tile, spectrum, Core.DFT_COMPLEX_OUTPUT)
        val planes = mutableListOf<Mat>()
        Core.split(spectrum, planes)
        planes.forEach(::centerZero)
        val magnitude = Mat()
        Core.magnitude(planes[0], planes[1], magnitude)
        val level = Mat()
        Core.add(magnitude, Scalar.all(1.0), level)
        Core.log(level, level)
        val local = Mat()
        Imgproc.blur(level, local, Size(9.0, 9.0))
        val rise = Mat()
        Core.subtract(level, local, rise)
        level.release()
        val peaks = Mat()
        Imgproc.threshold(rise, peaks, PEAK, 255.0, Imgproc.THRESH_BINARY)
        rise.release()
        peaks.convertTo(peaks, CvType.CV_8U)
        Core.bitwise_and(peaks, far, peaks)
        val grow = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(peaks, peaks, grow)
        grow.release()
        // At a peak, scale the coefficient down to the magnitude around it; elsewhere leave it.
        val target = Mat()
        Core.exp(local, target)
        Core.subtract(target, Scalar.all(1.0), target)
        local.release()
        Core.max(magnitude, Scalar.all(1e-6), magnitude)
        val scale = Mat()
        Core.divide(target, magnitude, scale)
        target.release()
        magnitude.release()
        val ones = Mat(scale.size(), CvType.CV_32F, Scalar.all(1.0))
        scale.copyTo(ones, peaks)
        scale.release()
        peaks.release()
        Core.multiply(planes[0], ones, planes[0])
        Core.multiply(planes[1], ones, planes[1])
        ones.release()
        planes.forEach(::centerZero)
        Core.merge(planes, spectrum)
        planes.forEach(Mat::release)
        val out = Mat()
        Core.idft(spectrum, out, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
        spectrum.release()
        return out
    }

    /** Swaps quadrants so frequency zero sits in the middle; doing it twice undoes it. */
    private fun centerZero(m: Mat) {
        val half = TILE / 2
        val q0 = m.submat(0, half, 0, half)
        val q1 = m.submat(0, half, half, TILE)
        val q2 = m.submat(half, TILE, 0, half)
        val q3 = m.submat(half, TILE, half, TILE)
        val tmp = Mat()
        q0.copyTo(tmp); q3.copyTo(q0); tmp.copyTo(q3)
        q1.copyTo(tmp); q2.copyTo(q1); tmp.copyTo(q2)
        listOf(q0, q1, q2, q3, tmp).forEach(Mat::release)
    }

    /** sin² in both directions: at half-tile steps, overlapping windows add up to exactly one. */
    private fun window(): Mat {
        val line = FloatArray(TILE) { sin(PI * (it + 0.5) / TILE).let { s -> (s * s).toFloat() } }
        val values = FloatArray(TILE * TILE) { line[it / TILE] * line[it % TILE] }
        return Mat(TILE, TILE, CvType.CV_32F).apply { put(0, 0, values) }
    }

    /** 255 where a centered spectrum's frequency is above [LOWEST]. */
    private fun farFromCenter(): Mat {
        val values = ByteArray(TILE * TILE) { i ->
            val fy = (i / TILE - TILE / 2).toDouble() / TILE
            val fx = (i % TILE - TILE / 2).toDouble() / TILE
            if (hypot(fx, fy) > LOWEST) 0xFF.toByte() else 0
        }
        return Mat(TILE, TILE, CvType.CV_8U).apply { put(0, 0, values) }
    }
}
