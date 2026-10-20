package dev.olaf.oscanner.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import dev.olaf.oscanner.core.model.Area
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tells print from handwriting by how the marks look, with a small network trained on printed
 * text in many typefaces and on handwriting photographed in the wild (GNHK, CC BY 4.0).
 *
 * The page's ink is grouped into runs of text; each run is looked at through windows 1.25 times
 * its height tall and 2.5 times wide, stepping one height along, and every mark takes the
 * verdict of the windows over it. A printed label and the handwriting filled in beside it are
 * one run, but their marks come out apart.
 */
class PrintSorter(context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        context.assets.open("print/sorter.onnx").use { it.readBytes() },
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )

    /**
     * Where the print is on [image]: one box per stretch of printed marks in a run, normalized
     * to the image. Everything else that's text-like is handwriting.
     */
    fun printAreas(image: Bitmap): List<Area> {
        val gray = toGray(image)
        val w = gray.cols()
        val h = gray.rows()
        val pixels = ByteArray(w * h).also { gray.get(0, 0, it) }
        val text = textInk(gray)
        gray.release()

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(text, labels, stats, centroids, 8, CvType.CV_32S)
        centroids.release()
        val st = IntArray(count * 5).also { stats.get(0, 0, it) }
        stats.release()
        fun left(i: Int) = st[i * 5]
        fun top(i: Int) = st[i * 5 + 1]
        fun width(i: Int) = st[i * 5 + 2]
        fun height(i: Int) = st[i * 5 + 3]
        fun area(i: Int) = st[i * 5 + 4]
        val marks = (1 until count).filter { area(it) >= MIN_AREA && height(it) >= MIN_HEIGHT }
        if (marks.isEmpty()) {
            listOf(text, labels).forEach(Mat::release)
            return emptyList()
        }
        val ids = IntArray(w * h).also { labels.get(0, 0, it) }
        labels.release()

        // Runs: marks a little more than a letter's height apart, side by side, are one run.
        val unit = marks.map { height(it) }.sorted()[marks.size / 2]
        val keep = Mat.zeros(h, w, CvType.CV_8UC1)
        val isMark = BooleanArray(count).also { b -> marks.forEach { b[it] = true } }
        keep.put(0, 0, ByteArray(w * h) { if (isMark[ids[it]]) -1 else 0 })
        text.release()
        Imgproc.dilate(keep, keep, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(((unit * 1.2).toInt() or 1).toDouble(), 1.0)))
        val runMat = Mat()
        Imgproc.connectedComponents(keep, runMat, 8, CvType.CV_32S)
        keep.release()
        val runIds = IntArray(w * h).also { runMat.get(0, 0, it) }
        runMat.release()
        val runs = marks.groupBy { mark ->
            // Any pixel of the mark tells its run.
            val x0 = left(mark)
            val y0 = top(mark)
            var found = 0
            loop@ for (y in y0 until y0 + height(mark)) for (x in x0 until x0 + width(mark)) {
                if (ids[y * w + x] == mark) {
                    found = runIds[y * w + x]
                    break@loop
                }
            }
            found
        }

        val areas = mutableListOf<Area>()
        for ((_, members) in runs) {
            val med = members.map { height(it) }.sorted()[members.size / 2]
            val main = members.filter { height(it) >= 0.3 * med }
            val x0 = main.minOf { left(it) }
            val x1 = main.maxOf { left(it) + width(it) } - 1
            val y0 = main.minOf { top(it) }
            val y1 = main.maxOf { top(it) + height(it) } - 1
            val runHeight = y1 - y0 + 1
            val (windows, centers) = windows(pixels, w, h, x0, x1, (y0 + y1) / 2, runHeight)
            if (centers.isEmpty()) continue
            val hand = score(windows, centers.size)
            // Each mark: the windows over it, nearer ones counting more.
            val reach = runHeight * TALL
            val sorted = members.sortedBy { left(it) }
            val own = sorted.associateWith { mark ->
                val cx = left(mark) + width(mark) / 2f
                var sum = 0.0
                var weight = 0.0
                centers.forEachIndexed { k, c ->
                    val d = abs(c - cx)
                    if (d <= reach) {
                        val wt = 1.0 - d / (reach + 1)
                        sum += hand[k] * wt
                        weight += wt
                    }
                }
                if (weight > 0) sum / weight else hand[centers.indices.minBy { abs(centers[it] - cx) }].toDouble()
            }
            // Words (marks closer than a letter gap) take their marks' verdict together, so the
            // end of a printed label isn't pulled over by handwriting written just after it.
            val verdict = HashMap<Int, Boolean>()
            var word = mutableListOf(sorted.first())
            var right = left(sorted.first()) + width(sorted.first())
            fun close(marksOfWord: List<Int>) {
                val total = marksOfWord.sumOf { area(it).toDouble() }
                val p = marksOfWord.sumOf { own.getValue(it) * area(it) } / total
                // A mark it's very sure is handwriting stays so, even touching a printed word.
                marksOfWord.forEach { verdict[it] = own.getValue(it) <= SURE_HAND && p < 0.5 }
            }
            for (mark in sorted.drop(1)) {
                if (left(mark) - right > WORD_GAP * runHeight) {
                    close(word)
                    word = mutableListOf()
                }
                word += mark
                right = max(right, left(mark) + width(mark))
            }
            close(word)
            val printed = sorted.map { it to verdict.getValue(it) }
            // Stretches of printed marks, side by side, as boxes.
            var start = -1
            for (k in 0..printed.size) {
                val isPrint = k < printed.size && printed[k].second
                if (isPrint && start < 0) start = k
                if (!isPrint && start >= 0) {
                    val stretch = printed.subList(start, k).map { it.first }
                    areas += Area(
                        stretch.minOf { left(it) }.toFloat() / w,
                        stretch.minOf { top(it) }.toFloat() / h,
                        stretch.maxOf { left(it) + width(it) }.toFloat() / w,
                        stretch.maxOf { top(it) + height(it) }.toFloat() / h,
                    )
                    start = -1
                }
            }
        }
        return areas
    }

    /** Chance each window is handwriting. */
    private fun score(windows: FloatArray, n: Int): FloatArray {
        val out = FloatArray(n)
        var at = 0
        while (at < n) {
            val batch = min(BATCH, n - at)
            val input = FloatBuffer.wrap(windows, at * WIN_H * WIN_W, batch * WIN_H * WIN_W).slice()
            OnnxTensor.createTensor(env, input, longArrayOf(batch.toLong(), 1, WIN_H.toLong(), WIN_W.toLong())).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = result[0].value as Array<FloatArray>
                    for (i in 0 until batch) out[at + i] = (1.0 / (1.0 + exp(-logits[i][0].toDouble()))).toFloat()
                }
            }
            at += batch
        }
        return out
    }

    /**
     * The run's windows, scaled to the network's size and normalized (paper 0, ink 1), with
     * each window's middle column. As in training.
     */
    private fun windows(pixels: ByteArray, w: Int, h: Int, x0: Int, x1: Int, cy: Int, runHeight: Int): Pair<FloatArray, List<Float>> {
        val wh = (runHeight * TALL).roundToInt()
        val ww = (runHeight * WIDE).roundToInt()
        val step = max(1, (runHeight * STEP).roundToInt())
        val first = x0 + ww / 2 - ww / 4
        val last = max(first, x1 - ww / 4)
        val centers = mutableListOf<Float>()
        val all = mutableListOf<FloatArray>()
        val crop = Mat(wh, ww, CvType.CV_8UC1)
        val small = Mat()
        val cropPixels = ByteArray(wh * ww)
        var cx = first
        while (cx <= last) {
            val topRow = cy - wh / 2
            val leftCol = cx - ww / 2
            for (y in 0 until wh) for (x in 0 until ww) {
                val sy = topRow + y
                val sx = leftCol + x
                cropPixels[y * ww + x] = if (sy in 0 until h && sx in 0 until w) pixels[sy * w + sx] else -1
            }
            crop.put(0, 0, cropPixels)
            Imgproc.resize(crop, small, Size(WIN_W.toDouble(), WIN_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            val px = ByteArray(WIN_W * WIN_H).also { small.get(0, 0, it) }
            all += normalize(px)
            centers += cx.toFloat()
            cx += step
        }
        listOf(crop, small).forEach(Mat::release)
        val flat = FloatArray(all.size * WIN_H * WIN_W)
        all.forEachIndexed { i, a -> a.copyInto(flat, i * WIN_H * WIN_W) }
        return flat to centers
    }

    /** Paper to 0 and the darkest ink to 1, from the window's own levels (as numpy percentiles). */
    private fun normalize(px: ByteArray): FloatArray {
        val v = FloatArray(px.size) { (px[it].toInt() and 0xFF).toFloat() }
        val sorted = v.sortedArray()
        val hi = percentile(sorted, 90.0)
        val lo = min(percentile(sorted, 2.0), hi - 25f)
        val range = max(hi - lo, 1f)
        return FloatArray(v.size) { ((hi - v[it]) / range).coerceIn(0f, 1f) }
    }

    private fun percentile(sorted: FloatArray, q: Double): Float {
        val pos = (sorted.size - 1) * q / 100.0
        val i = pos.toInt()
        val f = (pos - i).toFloat()
        return if (i + 1 < sorted.size) sorted[i] * (1 - f) + sorted[i + 1] * f else sorted[i]
    }

    /** Ink that isn't a long straight rule. */
    private fun textInk(gray: Mat): Mat {
        val side = max(gray.cols(), gray.rows())
        val ink = Mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, max(15, (side / 40) or 1), 15.0)
        val run = (side * 0.06)
        val thick = Mat()
        val rules = Mat()
        val down = Mat()
        Imgproc.dilate(ink, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 3.0)))
        Imgproc.morphologyEx(thick, rules, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(run, 1.0)))
        Imgproc.dilate(ink, thick, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 1.0)))
        Imgproc.morphologyEx(thick, down, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, run)))
        org.opencv.core.Core.bitwise_or(rules, down, rules)
        org.opencv.core.Core.bitwise_and(rules, ink, rules)
        Imgproc.dilate(rules, rules, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
        org.opencv.core.Core.subtract(ink, rules, ink)
        listOf(thick, rules, down).forEach(Mat::release)
        return ink
    }

    private fun toGray(bitmap: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }

    override fun close() = session.close()

    private companion object {
        const val WIN_H = 32
        const val WIN_W = 64
        const val TALL = 1.25
        const val WIDE = 2.5
        const val STEP = 1.0
        const val MIN_AREA = 6
        /** A space wider than this many run heights ends a word. */
        const val WORD_GAP = 0.35
        const val SURE_HAND = 0.85
        const val MIN_HEIGHT = 4
        const val BATCH = 64
        const val THREADS = 4
    }
}
