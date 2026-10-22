package dev.olaf.oscanner.feature.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.olaf.oscanner.core.imaging.Detection
import dev.olaf.oscanner.core.imaging.DocumentDetector
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Quad
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Runs page detection on the luminance plane of each preview frame. Lives on one analysis
 * thread, which owns its [DocumentDetector].
 */
internal class PageAnalyzer(
    private val mode: () -> CaptureMode,
    /**
     * Clockwise degrees from the camera's frames to the screen, which stays upright in portrait
     * while scanning: the sensor's mounting angle. Not the frame's own rotation, which follows
     * the phone as it turns, because the camera keeps photos upright that way.
     */
    private val toScreen: Int,
    private val onCode: (ScannedCode) -> Unit,
    private val onResult: (quad: Quad?, sharpness: Double, frameAspect: Float, glare: Boolean, signals: FrameSignals) -> Unit,
    /**
     * The learned page finder, for Book mode only: it sees a whole spread where the edge search
     * finds one page. Not used for other modes, where on real frames it added outlines in the
     * wrong places.
     */
    private val bookFinder: () -> dev.olaf.oscanner.core.imaging.PageFinder? = { null },
) : ImageAnalysis.Analyzer {

    // Created on the analysis thread, after OpenCV's native code is loaded.
    private var detector: DocumentDetector? = null
    private var detectorMode: CaptureMode? = null
    private var scaled: Mat? = null
    /** Where the page was in the last frame, and how many frames running it was only followed. */
    private var lastQuad: Quad? = null
    private var followedFrames = 0
    private val codes by lazy { CodeReader() }
    private val chroma = arrayOf(ByteArray(0), ByteArray(0))
    private var color: Mat? = null

    override fun analyze(image: ImageProxy) {
        image.use {
            val current = mode()
            if (current == CaptureMode.Code) {
                codes.read(image)?.let(onCode)
                return
            }
            PageRenderer.ensureLoaded()
            if (detectorMode != current) {
                detector?.release()
                detector = null
                detectorMode = current
                lastQuad = null
            }
            val detector = detector ?: DocumentDetector(
                angleTolerance = if (current == CaptureMode.Whiteboard || current == CaptureMode.Slides) 50.0 else 35.0,
                keepSpreads = current == CaptureMode.Book,
                cards = current == CaptureMode.IdCard,
                finder = if (current == CaptureMode.Book) bookFinder() else null,
            ).also { detector = it }
            val plane = image.planes[0]
            val luminance = Mat(image.height, image.width, CvType.CV_8UC1, plane.buffer, plane.rowStride.toLong())
            val longSide = max(image.width, image.height)
            val frame = if (longSide > MAX_SIDE) {
                val f = TARGET_SIDE.toDouble() / longSide
                val target = scaled ?: Mat().also { scaled = it }
                Imgproc.resize(luminance, target, Size(), f, f, Imgproc.INTER_AREA)
                target
            } else {
                luminance
            }
            // A page is followed from frame to frame for a while, but must be found afresh now
            // and then, so an outline can't wander off on its own.
            val inGray = detector.detect(frame, lastQuad.takeIf { followedFrames < MAX_FOLLOWED })
            // A card is often a pale tint on a gray desk, plain in brightness: its colors are
            // searched too, unless it was simply followed from the frame before.
            val detection = if (current == CaptureMode.IdCard && !inGray.followed) {
                val inColor = colorfulness(image)?.let { color -> detector.detectInColor(color).quad }
                val card = DocumentDetector.wholeCard(inGray.quad, inColor)
                if (card === inGray.quad) inGray else Detection(card, inGray.sharpness)
            } else {
                inGray
            }
            lastQuad = detection.quad
            followedFrames = if (detection.quad != null && detection.followed) followedFrames + 1 else 0
            val glare = detection.quad?.let { Glare.onPage(frame, it) } ?: false
            val signals = FrameSampler.measure(image, detection.quad, Core.mean(frame).`val`[0])
            luminance.release()
            val rotation = toScreen
            val upright = detection.quad?.let { QuadGeometry.rotate(it, rotation) }
            val aspect = if (rotation % 180 == 0) {
                image.width.toFloat() / image.height
            } else {
                image.height.toFloat() / image.width
            }
            onResult(upright, detection.sharpness, aspect, glare, signals)
        }
    }

    /** How colorful each pixel of [image] is (see [DocumentDetector.colorfulness]), at half size. */
    private fun colorfulness(image: ImageProxy): Mat? {
        if (image.planes.size < 3) return null
        val w = image.width / 2
        val h = image.height / 2
        fun plane(i: Int): Mat {
            val p = image.planes[i]
            val buffer = p.buffer
            val bytes = chroma[i - 1].takeIf { it.size == w * h } ?: ByteArray(w * h).also { chroma[i - 1] = it }
            for (y in 0 until h) {
                val row = y * p.rowStride
                for (x in 0 until w) bytes[y * w + x] = buffer.get(row + x * p.pixelStride)
            }
            return Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, bytes) }
        }
        val u = plane(1)
        val v = plane(2)
        val out = color ?: Mat().also { color = it }
        DocumentDetector.colorfulness(u, v, out)
        u.release()
        v.release()
        return out
    }

    /** Call from the analysis thread after the analyzer is detached. */
    fun release() {
        detector?.release()
        detector = null
        scaled?.release()
        scaled = null
        color?.release()
        color = null
    }

    private companion object {
        const val MAX_FOLLOWED = 10
        const val MAX_SIDE = 720
        const val TARGET_SIDE = 640
    }
}
