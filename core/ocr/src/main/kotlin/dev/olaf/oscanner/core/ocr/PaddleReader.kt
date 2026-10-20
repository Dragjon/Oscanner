package dev.olaf.oscanner.core.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A page reader: an image in, its words with boxes and the text in reading order out. */
interface PageReader : AutoCloseable {
    fun read(image: Bitmap): OcrResult
}

/**
 * Reads English with the PP-OCR models (PaddleOCR, Apache-2.0), on ONNX Runtime: a DBNet
 * that finds every line of text, then a recognizer that reads each line. Both are much better
 * than Tesseract on photographed, faint or slightly bent text. Each word's position comes from
 * where the recognizer read its letters along the line; [WordFixer] then corrects words the
 * recognizer had doubts about. Not thread-safe; [close] when done.
 */
class PaddleReader(context: Context) : PageReader {

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(THREADS)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val detector: OrtSession
    private val recognizer: OrtSession
    /** Class 0 is CTC's blank; the dictionary follows, then a space. */
    private val alphabet: List<String>
    private val fixer: WordFixer

    init {
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
        val assets = context.assets
        detector = env.createSession(assets.open("paddle/det.onnx").use { it.readBytes() }, options)
        recognizer = env.createSession(assets.open("paddle/rec_en.onnx").use { it.readBytes() }, options)
        alphabet = listOf("") + assets.open("paddle/rec_en_dict.txt").bufferedReader().readLines() + " "
        fixer = WordFixer.get(assets)
    }

    override fun read(image: Bitmap): OcrResult {
        val rgba = Mat()
        Utils.bitmapToMat(image, rgba)
        // The models were trained on OpenCV's BGR images.
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        try {
            val w = bgr.cols().toDouble()
            val h = bgr.rows().toDouble()
            val lines = findLines(bgr).mapNotNull { box -> readLine(bgr, box, w, h) }
            return assemble(lines)
        } finally {
            bgr.release()
        }
    }

    override fun close() {
        detector.close()
        recognizer.close()
        options.close()
    }

    /** A line as found and read: its outline (TL, TR, BR, BL in pixels) and its words. */
    private class Line(val corners: List<Point>, val words: List<OcrWord>)

    /**
     * DBNet: a map of how likely each pixel is text, thresholded into blobs; each blob's
     * rotated rectangle, grown back out by the margin the model was trained to shrink it by.
     */
    private fun findLines(bgr: Mat): List<List<Point>> {
        val scale = DET_SIDE.toDouble() / max(bgr.cols(), bgr.rows())
        val dw = max(32, (bgr.cols() * scale / 32).roundToInt() * 32)
        val dh = max(32, (bgr.rows() * scale / 32).roundToInt() * 32)
        val small = Mat()
        Imgproc.resize(bgr, small, Size(dw.toDouble(), dh.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val input = FloatArray(3 * dw * dh)
        val pixels = ByteArray(dw * dh * 3)
        small.get(0, 0, pixels)
        small.release()
        val plane = dw * dh
        for (i in 0 until plane) {
            for (c in 0 until 3) {
                val v = (pixels[i * 3 + c].toInt() and 0xFF) / 255f
                input[c * plane + i] = (v - MEAN[c]) / STD[c]
            }
        }
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, dh.toLong(), dw.toLong())).use { tensor ->
            detector.run(mapOf(detector.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }
        val map = Mat(dh, dw, CvType.CV_32F)
        map.put(0, 0, prob)
        val mask = Mat()
        Imgproc.threshold(map, mask, BINARY_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
        mask.convertTo(mask, CvType.CV_8U)
        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        val sx = bgr.cols().toDouble() / dw
        val sy = bgr.rows().toDouble() / dh
        val blob = Mat.zeros(dh, dw, CvType.CV_8U)
        val boxes = contours.mapNotNull { contour ->
            try {
                if (contour.rows() < 4) return@mapNotNull null
                val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
                if (min(rect.size.width, rect.size.height) < MIN_SIDE) return@mapNotNull null
                // How sure the model is, on average, over the blob.
                val bounds = Imgproc.boundingRect(contour)
                blob.setTo(Scalar(0.0))
                Imgproc.drawContours(blob, listOf(contour), -1, Scalar(255.0), -1)
                val score = Core.mean(map.submat(bounds), blob.submat(bounds)).`val`[0]
                if (score < BOX_THRESHOLD) return@mapNotNull null
                val grow = rect.size.width * rect.size.height * UNCLIP / (2 * (rect.size.width + rect.size.height))
                rect.size = Size(rect.size.width + 2 * grow, rect.size.height + 2 * grow)
                val corners = arrayOfNulls<Point>(4)
                rect.points(corners)
                order(corners.map { Point(it!!.x * sx, it.y * sy) })
            } finally {
                contour.release()
            }
        }
        listOf(map, mask, blob).forEach(Mat::release)
        return boxes
    }

    /** Clockwise from top-left: smallest x + y first, largest last; the others by x − y. */
    private fun order(points: List<Point>): List<Point> {
        val tl = points.minBy { it.x + it.y }
        val br = points.maxBy { it.x + it.y }
        val tr = points.maxBy { it.x - it.y }
        val bl = points.minBy { it.x - it.y }
        return listOf(tl, tr, br, bl)
    }

    private fun readLine(bgr: Mat, corners: List<Point>, w: Double, h: Double): Line? {
        val (tl, tr, br, bl) = corners
        val width = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y)).roundToInt()
        val height = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y)).roundToInt()
        if (width < 2 || height < 2) return null
        // Tall and narrow means text running down the page; read it turned.
        val upright = height < width * 1.5
        val warp = Imgproc.getPerspectiveTransform(
            MatOfPoint2f(tl, tr, br, bl),
            MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0), Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())),
        )
        val crop = Mat()
        Imgproc.warpPerspective(bgr, crop, warp, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
        warp.release()
        if (!upright) Core.rotate(crop, crop, Core.ROTATE_90_COUNTERCLOCKWISE)
        val (letters, steps) = try {
            recognize(crop)
        } finally {
            crop.release()
        }
        if (letters.isEmpty()) return null

        // Words: runs between spaces, each with the steps where its letters were read.
        val words = mutableListOf<List<Pair<LetterGuess, Int>>>()
        var current = mutableListOf<Pair<LetterGuess, Int>>()
        for (letter in letters) {
            if (letter.first.best == ' ') {
                if (current.isNotEmpty()) words += current
                current = mutableListOf()
            } else {
                current += letter
            }
        }
        if (current.isNotEmpty()) words += current
        if (words.isEmpty()) return null
        val texts = fixer.fix(words.map { word -> word.map { it.first } })
        val stepCount = steps.toDouble()
        // Half a letter's width, as a share of the line.
        val half = 0.5 / letters.size

        val placed = words.mapIndexed { i, word ->
            val confidence = word.map { it.first.confidence }.average().toFloat() * 100f
            if (!upright) {
                // Turned text: the whole line's box for each word is close enough.
                val xs = corners.map { it.x }
                val ys = corners.map { it.y }
                return@mapIndexed OcrWord(texts[i], (xs.min() / w).toFloat(), (ys.min() / h).toFloat(), (xs.max() / w).toFloat(), (ys.max() / h).toFloat(), confidence)
            }
            // Where along the line its letters were read, as a share of the line's length,
            // padded half a letter each side.
            val a = (word.first().second / stepCount - half).coerceIn(0.0, 1.0)
            val b = ((word.last().second + 1) / stepCount + half).coerceIn(0.0, 1.0)
            val points = listOf(lerp(tl, tr, a), lerp(tl, tr, b), lerp(bl, br, a), lerp(bl, br, b))
            OcrWord(
                texts[i],
                (points.minOf { it.x } / w).toFloat().coerceIn(0f, 1f),
                (points.minOf { it.y } / h).toFloat().coerceIn(0f, 1f),
                (points.maxOf { it.x } / w).toFloat().coerceIn(0f, 1f),
                (points.maxOf { it.y } / h).toFloat().coerceIn(0f, 1f),
                confidence,
            )
        }
        return Line(corners, placed)
    }

    private fun lerp(a: Point, b: Point, t: Double) = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

    /**
     * The recognizer on one line: scaled to its 48 px input height, it scores every character
     * at each step along the line; CTC keeps each run's best, dropping blanks and repeats.
     * Returns each letter with its runners-up and the step it was read at, and how many steps
     * the line took.
     */
    private fun recognize(crop: Mat): Pair<List<Pair<LetterGuess, Int>>, Int> {
        val width = ceil(REC_HEIGHT.toDouble() * crop.cols() / crop.rows()).toInt().coerceIn(MIN_REC_WIDTH, MAX_REC_WIDTH)
        val sized = Mat()
        Imgproc.resize(crop, sized, Size(width.toDouble(), REC_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val pixels = ByteArray(width * REC_HEIGHT * 3)
        sized.get(0, 0, pixels)
        sized.release()
        val plane = width * REC_HEIGHT
        val input = FloatArray(3 * plane)
        for (i in 0 until plane) {
            for (c in 0 until 3) input[c * plane + i] = ((pixels[i * 3 + c].toInt() and 0xFF) / 255f - 0.5f) / 0.5f
        }
        val (steps, classes, scores) = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), width.toLong())).use { tensor ->
            recognizer.run(mapOf(recognizer.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val buffer = out.floatBuffer
                Triple(shape[1].toInt(), shape[2].toInt(), FloatArray(buffer.remaining()).also { buffer.get(it) })
            }
        }
        val letters = mutableListOf<Pair<LetterGuess, Int>>()
        var previous = -1
        for (t in 0 until steps) {
            val row = t * classes
            var best = 0
            for (k in 1 until classes) if (scores[row + k] > scores[row + best]) best = k
            if (best != 0 && best != previous && best < alphabet.size) {
                val top = (0 until classes)
                    .filter { it != 0 && it < alphabet.size && alphabet[it].length == 1 }
                    .sortedByDescending { scores[row + it] }
                    .take(3)
                    .map { alphabet[it][0] to scores[row + it] }
                if (top.isNotEmpty() && alphabet[best].length == 1) letters += LetterGuess(top) to t
            }
            previous = best
        }
        return letters to steps
    }

    /** Lines into reading order: rows top to bottom (lines whose middles share a row), each left to right. */
    private fun assemble(lines: List<Line>): OcrResult {
        val sorted = lines.sortedBy { line -> line.corners.sumOf { it.y } / 4 }
        val rows = mutableListOf<MutableList<Line>>()
        for (line in sorted) {
            val mid = line.corners.sumOf { it.y } / 4
            val height = (line.corners[3].y - line.corners[0].y + line.corners[2].y - line.corners[1].y) / 2
            val row = rows.lastOrNull()?.takeIf { row ->
                val rowMid = row.sumOf { l -> l.corners.sumOf { it.y } / 4 } / row.size
                kotlin.math.abs(rowMid - mid) < height * 0.5
            }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        val ordered = rows.map { row -> row.sortedBy { line -> line.corners.minOf { it.x } } }
        val text = ordered.joinToString("\n") { row -> row.joinToString(" ") { line -> line.words.joinToString(" ") { it.text } } }
        return OcrResult(text, ordered.flatten().flatMap { it.words })
    }

    private companion object {
        const val THREADS = 4
        /** The long side the page is scaled to for finding lines: small print stays legible. */
        const val DET_SIDE = 1600
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        const val BINARY_THRESHOLD = 0.3
        const val BOX_THRESHOLD = 0.6
        const val UNCLIP = 1.5
        const val MIN_SIDE = 3.0
        const val REC_HEIGHT = 48
        const val MIN_REC_WIDTH = 16
        const val MAX_REC_WIDTH = 3200
    }
}
