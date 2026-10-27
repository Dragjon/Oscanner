package dev.olaf.oscanner.core.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Asked first by [DocumentDetector]: where a document's four corners are, straight from the frame.
 *
 * Given the frame in gray, it returns the outline, normalized to the frame, or null when it isn't
 * sure of all four corners. It knows whole documents and cards; a page running out of view or an
 * open book is left to the detector's own search.
 */
fun interface CornerFinder {
    fun corners(gray: Mat): Quad?
}

/**
 * DocAligner's heatmap model (DocsaidLab, Apache-2.0; LCNet-100 backbone with a BiFPN neck),
 * trained on photos of real documents and cards: it looks at the frame squeezed to 256x256 and
 * draws a heatmap for each corner at 128x128. A corner counts where its heatmap is above 0.3, at
 * the heat-weighted middle of its largest such patch, as DocAligner's own code takes it; the
 * outline counts only if every corner's heatmap peaks at [SURE] or more. It was trained on color
 * and is given gray here: on cards it does as well, on documents on a cluttered desk worse, but
 * still better than the detector's search. [model] is the one for pages: DocAligner's, tuned
 * further on phone photos of paper (tools/page-finder/cornertrain.py) and blended back towards
 * where it started, so it keeps what it knew of pages among clutter. [CARDS] is DocAligner's own,
 * which holds a card's corners steadier. Not thread-safe: one per thread.
 */
class CornerNet(context: Context, model: String = PAGES) : CornerFinder, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        context.assets.open(model).use { it.readBytes() },
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val small = Mat()
    private val pixels = ByteArray(SIDE * SIDE)
    private val input = FloatArray(3 * SIDE * SIDE)

    override fun corners(gray: Mat): Quad? {
        // A frame longer than 4:3, a tall photo from the gallery, is padded out to 4:3 first:
        // squeezed into the square input whole, a card in it comes out too thin to be seen.
        val w = gray.cols()
        val h = gray.rows()
        val wide = if (h >= w) max(w, (h * 3 + 2) / 4) else w
        val tall = if (w > h) max(h, (w * 3 + 2) / 4) else h
        val left = (wide - w) / 2
        val top = (tall - h) / 2
        val frame = if (wide == w && tall == h) gray else Mat().also {
            Core.copyMakeBorder(gray, it, top, tall - h - top, left, wide - w - left, Core.BORDER_REPLICATE)
        }
        Imgproc.resize(frame, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        if (frame !== gray) frame.release()
        small.get(0, 0, pixels)
        val plane = SIDE * SIDE
        for (i in 0 until plane) {
            val v = (pixels[i].toInt() and 0xFF) / 255f
            input[i] = v
            input[plane + i] = v
            input[2 * plane + i] = v
        }
        val heat = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<Array<FloatArray>>>)[0]
            }
        }
        val found = heat.map { corner(it) ?: return null }
        // Back from the padded frame to [gray].
        return QuadGeometry.order(found.map { Corner((it.x * wide - left) / w, (it.y * tall - top) / h) })
    }

    /** One corner from its heatmap, or null if the heatmap isn't sure of it anywhere. */
    private fun corner(map: Array<FloatArray>): Corner? {
        val h = map.size
        val w = map[0].size
        if (map.maxOf { row -> row.max() } < SURE) return null
        // The patches above the threshold, by flood fill; the largest wins.
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        var best = -1
        var bestSize = 0
        var next = 0
        for (start in 0 until w * h) {
            if (label[start] != 0 || map[start / w][start % w] < PATCH) continue
            next++
            label[start] = next
            var top = 0
            var size = 0
            stack[top++] = start
            while (top > 0) {
                val i = stack[--top]
                size++
                val x = i % w
                val y = i / w
                for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (label[j] == 0 && map[ny][nx] >= PATCH) {
                        label[j] = next
                        stack[top++] = j
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size
                best = next
            }
        }
        var sx = 0.0
        var sy = 0.0
        var sum = 0.0
        for (i in 0 until w * h) {
            if (label[i] != best) continue
            val v = map[i / w][i % w].toDouble()
            sx += v * (i % w + 0.5)
            sy += v * (i / w + 0.5)
            sum += v
        }
        if (sum <= 0.0) return null
        return Corner((sx / sum / w).toFloat(), (sy / sum / h).toFloat())
    }

    override fun close() {
        session.close()
        small.release()
    }

    companion object {
        const val PAGES = "detect/page_corners.onnx"
        const val CARDS = "detect/corners.onnx"
        private const val SIDE = 256
        /** A corner's heatmap must peak at least this high; on frames with no document it stays near 0. */
        const val SURE = 0.5f
        /** DocAligner's own threshold for the patch a corner is taken from. */
        private const val PATCH = 0.3f
    }
}

/**
 * This finder for frames that reach the screen turned [toScreen] degrees clockwise, as a camera's
 * do: each frame is turned upright for it, as documents are photographed, and its outline turned
 * back to the frame.
 */
fun CornerFinder.turned(toScreen: Int): CornerFinder {
    val there = when (Math.floorMod(toScreen, 360)) {
        90 -> Core.ROTATE_90_CLOCKWISE
        180 -> Core.ROTATE_180
        270 -> Core.ROTATE_90_COUNTERCLOCKWISE
        else -> return this
    }
    return CornerFinder { gray ->
        val upright = Mat()
        Core.rotate(gray, upright, there)
        val quad = corners(upright)
        upright.release()
        quad?.let { QuadGeometry.rotate(it, 360 - Math.floorMod(toScreen, 360)) }
    }
}
