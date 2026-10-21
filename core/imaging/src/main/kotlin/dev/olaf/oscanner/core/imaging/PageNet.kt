package dev.olaf.oscanner.core.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * A small network trained for Oscanner on drawn scenes of pages on surfaces of every kind: it
 * looks at the frame squeezed to 96x128 in gray and marks the page, at 48x64. Not thread-safe:
 * one per thread, like the detector it helps.
 */
class PageNet(context: Context) : PageFinder, AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val session = env.createSession(
        context.assets.open("detect/page.onnx").use { it.readBytes() },
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        },
    )
    private val small = Mat()
    private val input = FloatArray(IN_W * IN_H)
    private val pixels = ByteArray(IN_W * IN_H)

    override fun pageMask(gray: Mat): Mat? {
        Imgproc.resize(gray, small, Size(IN_W.toDouble(), IN_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        small.get(0, 0, pixels)
        for (i in pixels.indices) input[i] = (pixels[i].toInt() and 0xFF) / 255f
        val logits = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 1, IN_H.toLong(), IN_W.toLong())).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
            }
        }
        val h = logits.size
        val w = logits[0].size
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = (255.0 / (1.0 + exp(-logits[y][x].toDouble()))).toInt().toByte()
        }
        return Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, out) }
    }

    override fun close() {
        session.close()
        small.release()
    }

    private companion object {
        const val IN_W = 96
        const val IN_H = 128
    }
}
