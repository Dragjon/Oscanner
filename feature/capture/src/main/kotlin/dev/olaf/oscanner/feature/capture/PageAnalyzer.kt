package dev.olaf.oscanner.feature.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import dev.olaf.oscanner.core.imaging.DocumentDetector
import dev.olaf.oscanner.core.imaging.PageRenderer
import dev.olaf.oscanner.core.imaging.QuadGeometry
import dev.olaf.oscanner.core.model.Quad
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
    private val onCode: (ScannedCode) -> Unit,
    private val onResult: (quad: Quad?, sharpness: Double, frameAspect: Float) -> Unit,
) : ImageAnalysis.Analyzer {

    // Created on the analysis thread, after OpenCV's native code is loaded.
    private var detector: DocumentDetector? = null
    private var detectorTolerance = 0.0
    private var scaled: Mat? = null
    private val codes by lazy { CodeReader() }

    override fun analyze(image: ImageProxy) {
        image.use {
            val current = mode()
            if (current == CaptureMode.Code) {
                codes.read(image)?.let(onCode)
                return
            }
            PageRenderer.ensureLoaded()
            val tolerance = if (current == CaptureMode.Whiteboard) 50.0 else 35.0
            if (detectorTolerance != tolerance) {
                detector?.release()
                detector = null
                detectorTolerance = tolerance
            }
            val detector = detector ?: DocumentDetector(tolerance).also { detector = it }
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
            val detection = detector.detect(frame)
            luminance.release()
            val rotation = image.imageInfo.rotationDegrees
            val upright = detection.quad?.let { QuadGeometry.rotate(it, rotation) }
            val aspect = if (rotation % 180 == 0) {
                image.width.toFloat() / image.height
            } else {
                image.height.toFloat() / image.width
            }
            onResult(upright, detection.sharpness, aspect)
        }
    }

    /** Call from the analysis thread after the analyzer is detached. */
    fun release() {
        detector?.release()
        detector = null
        scaled?.release()
        scaled = null
    }

    private companion object {
        const val MAX_SIDE = 720
        const val TARGET_SIDE = 640
    }
}
