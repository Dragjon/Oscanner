package dev.olaf.oscanner.feature.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.olaf.oscanner.core.imaging.CornerFinder
import dev.olaf.oscanner.core.imaging.DocumentDetector
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.imaging.turned
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
    /**
     * The same network trained on ID cards, for ID card mode: a pale card on a pale sheet shows
     * only its thin edge, too faint for the edge search on its own.
     */
    private val cardFinder: () -> dev.olaf.oscanner.core.imaging.PageFinder? = { null },
    /**
     * The corner network, for Document and ID card modes: it finds a whole page or card outright,
     * steadier than following edges. Book mode needs spreads, which it doesn't know. It takes
     * several frames' time, so it runs on its own thread on the latest frame whenever it's free,
     * and each outline it finds is checked against the frame it reaches and held until the next:
     * following edges in between brought back the jumps. Only when it misses twice running does
     * the edge search take over (a page running out of view, an open book).
     */
    private val cornerFinder: () -> CornerFinder? = { null },
) : ImageAnalysis.Analyzer {

    // Created on the analysis thread, after OpenCV's native code is loaded.
    private var detector: DocumentDetector? = null
    private var detectorMode: CaptureMode? = null
    private var scaled: Mat? = null
    /** Where the page was in the last frame, and how many frames running it was only followed. */
    private var lastQuad: Quad? = null
    private var followedFrames = 0
    private val codes by lazy { CodeReader() }

    private var corners: CornerFinder? = null
    private val netThread = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val netBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var netOutline: NetOutline? = null
    private var netUsed = 0L
    /** The network's last outline that held up, and how many of its outlines since didn't. */
    private var held: Quad? = null
    private var misses = 0
    private var frameNumber = 0L

    /** An outline from the corner network, null if it saw none, and the frame it saw it in. */
    private class NetOutline(val mode: CaptureMode, val frame: Long, val quad: Quad?)

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
                held = null
                misses = 0
                corners = when (current) {
                    CaptureMode.Document, CaptureMode.IdCard -> cornerFinder()?.turned(toScreen)
                    else -> null
                }
            }
            frameNumber++
            val detector = detector ?: DocumentDetector(
                angleTolerance = if (current == CaptureMode.Whiteboard || current == CaptureMode.Slides) 50.0 else 35.0,
                keepSpreads = current == CaptureMode.Book,
                cards = current == CaptureMode.IdCard,
                // The camera's frames are sideways to the finder, which knows only upright ones.
                finder = when (current) {
                    CaptureMode.Book -> bookFinder()
                    CaptureMode.IdCard -> cardFinder()
                    else -> null
                }?.turned(toScreen),
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
            corners?.let { net ->
                if (netBusy.compareAndSet(false, true)) {
                    val copy = frame.clone()
                    val n = frameNumber
                    netThread.execute {
                        try {
                            netOutline = NetOutline(current, n, runCatching { net.corners(copy) }.getOrNull())
                        } finally {
                            copy.release()
                            netBusy.set(false)
                        }
                    }
                }
            }
            // The network's latest outline, once, if it's for this mode and the page is there.
            val fresh = netOutline?.takeIf { it.mode == current && it.frame > netUsed }
            val checked = fresh?.let { outline ->
                netUsed = outline.frame
                outline.quad?.let { detector.withCorners(frame, it) }.also { found ->
                    if (found != null) {
                        held = found.quad
                        misses = 0
                    } else if (++misses >= NET_MISSES) {
                        held = null
                    }
                }
            }
            // A page is followed from frame to frame for a while, but must be found afresh now
            // and then, so an outline can't wander off on its own.
            val detection = checked
                ?: held?.let { detector.held(frame, it) }
                ?: detector.detect(frame, lastQuad.takeIf { followedFrames < MAX_FOLLOWED })
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

    /** Call from the analysis thread after the analyzer is detached. */
    fun release() {
        // The network may be mid-frame: it's closed only once its thread is done.
        netThread.shutdown()
        netThread.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        detector?.release()
        detector = null
        scaled?.release()
        scaled = null
    }

    private companion object {
        const val MAX_FOLLOWED = 10
        const val NET_MISSES = 2
        const val MAX_SIDE = 720
        const val TARGET_SIDE = 640
    }
}
