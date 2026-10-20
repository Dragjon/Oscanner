package dev.olaf.oscanner.feature.capture

import androidx.camera.core.ImageProxy
import dev.olaf.oscanner.core.model.Quad
import java.nio.ByteBuffer

/** What one preview frame shows besides the page outline, for the camera's hints. */
data class FrameSignals(
    /** Mean brightness, 0–255. */
    val brightness: Double,
    /** Share of points along the page's edges that look like skin: fingers holding it. */
    val skinAtEdges: Float,
    /** Share of points on the page that look like colored pen. */
    val penOnPage: Float,
)

/**
 * Samples the frame's YUV planes at a few hundred points: enough to notice a thumb or pen
 * marks, cheap enough for every frame. [quad] is in the frame's own normalized coordinates.
 */
internal object FrameSampler {

    fun measure(image: ImageProxy, quad: Quad?, brightness: Double): FrameSignals {
        if (quad == null || image.planes.size < 3) return FrameSignals(brightness, 0f, 0f)
        val planes = Planes(image)
        val c = quad.corners
        val cx = c.map { it.x }.average().toFloat()
        val cy = c.map { it.y }.average().toFloat()
        fun inward(x: Float, y: Float, by: Float) = (x + (cx - x) * by) to (y + (cy - y) * by)

        // The paper's own tint, from the middle of the page, so warm light isn't taken for skin.
        val paperCr = (0 until 9).map { i ->
            val (x, y) = inward(c[i % 4].x, c[i % 4].y, 0.5f + 0.1f * (i / 4))
            planes.v(x, y)
        }.sorted()[4]

        // Fingers hold a page by its edges: a band just inside the outline.
        var edgePoints = 0
        var skin = 0
        for (side in 0 until 4) {
            val a = c[side]
            val b = c[(side + 1) % 4]
            for (step in 1 until EDGE_STEPS) {
                val t = step.toFloat() / EDGE_STEPS
                for (depth in floatArrayOf(0.04f, 0.10f)) {
                    val (x, y) = inward(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, depth)
                    edgePoints++
                    if (isSkin(planes.y(x, y), planes.u(x, y), planes.v(x, y), paperCr)) skin++
                }
            }
        }

        // Colored pen: clearly tinted and darker than paper, somewhere on the page.
        var pagePoints = 0
        var pen = 0
        for (i in 1 until GRID) for (j in 1 until GRID) {
            val s = i.toFloat() / GRID
            val t = j.toFloat() / GRID
            // Bilinear across the outline.
            val topX = c[0].x + (c[1].x - c[0].x) * s
            val topY = c[0].y + (c[1].y - c[0].y) * s
            val botX = c[3].x + (c[2].x - c[3].x) * s
            val botY = c[3].y + (c[2].y - c[3].y) * s
            val x = topX + (botX - topX) * t
            val y = topY + (botY - topY) * t
            pagePoints++
            val luma = planes.y(x, y)
            val u = planes.u(x, y)
            val v = planes.v(x, y)
            val tint = kotlin.math.abs(u - 128) + kotlin.math.abs(v - 128)
            if (luma < 170 && tint > PEN_TINT && !isSkin(luma, u, v, paperCr)) pen++
        }
        return FrameSignals(brightness, skin.toFloat() / edgePoints, pen.toFloat() / pagePoints)
    }

    /** Skin tones, light to dark, in YCbCr; and redder than this page's paper. */
    private fun isSkin(y: Int, u: Int, v: Int, paperCr: Int): Boolean =
        y > 50 && v in 138..175 && u in 85..130 && v > paperCr + 8

    private const val EDGE_STEPS = 24
    private const val GRID = 20
    private const val PEN_TINT = 36

    /** Reads Y, U and V at normalized frame points, whatever the planes' strides. */
    private class Planes(image: ImageProxy) {
        private val w = image.width
        private val h = image.height
        private val yPlane = image.planes[0]
        private val uPlane = image.planes[1]
        private val vPlane = image.planes[2]

        fun y(x: Float, y: Float) = read(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, px(x), py(y))
        fun u(x: Float, y: Float) = read(uPlane.buffer, uPlane.rowStride, uPlane.pixelStride, px(x) / 2, py(y) / 2)
        fun v(x: Float, y: Float) = read(vPlane.buffer, vPlane.rowStride, vPlane.pixelStride, px(x) / 2, py(y) / 2)

        private fun px(x: Float) = (x * w).toInt().coerceIn(0, w - 1)
        private fun py(y: Float) = (y * h).toInt().coerceIn(0, h - 1)

        private fun read(buffer: ByteBuffer, rowStride: Int, pixelStride: Int, x: Int, y: Int): Int {
            val index = y * rowStride + x * pixelStride
            return if (index in 0 until buffer.limit()) buffer.get(index).toInt() and 0xFF else 128
        }
    }
}
