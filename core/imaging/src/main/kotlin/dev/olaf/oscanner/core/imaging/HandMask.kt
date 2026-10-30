package dev.olaf.oscanner.core.imaging

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a page's handwriting is, pixel by pixel, from a small network trained for Oscanner
 * (tools/handwriting-mask): printed pages drawn with real handwriting (GNHK) and pen marks on
 * them, and photos of marked exam papers. It sees the page in color at about [SIDE] pixels long
 * and gives each pixel a chance of being handwriting; where pen crosses print, the print's pixels
 * are print.
 *
 * A page takes a few seconds, so each page's answer is kept, in memory and in the cache folder,
 * under a digest of how the page looks: a page drawn again at another size, or for reading, finds
 * it there. Off until [install]; if the network can't be loaded, clean-up does without it.
 */
object HandMask {

    @Volatile private var context: Context? = null
    private var tried = false
    private var loaded: OrtSession? = null

    /** The network, loaded on first use once [install]ed; null if it can't be. */
    private val session: OrtSession?
        @Synchronized get() {
            if (tried) return loaded
            val app = context ?: return null
            tried = true
            loaded = runCatching {
                OrtEnvironment.getEnvironment().createSession(
                    app.assets.open(MODEL).use { it.readBytes() },
                    OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(4)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    },
                )
            }.onFailure { Log.w(TAG, "Handwriting network failed to load", it) }.getOrNull()
            return loaded
        }
    private val cacheDir: File? by lazy { context?.let { File(it.cacheDir, "hand").apply { mkdirs() } } }
    private val recent = LinkedHashMap<String, Mat>()

    /** Where to find the network; it's loaded when first needed. */
    fun install(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The chance that each pixel of [rgb] (8-bit RGB) is handwriting, 0 to 255, at [rgb]'s size;
     * null when the network isn't there.
     */
    fun of(rgb: Mat): Mat? {
        val net = session ?: return null
        val key = digest(rgb)
        val small = synchronized(this) { recent[key]?.clone() } ?: fromDisk(key) ?: run {
            val start = System.nanoTime()
            runCatching { run(net, rgb) }
                .onFailure { Log.w(TAG, "Handwriting network failed", it) }.getOrNull()
                ?.also { Log.i(TAG, "Page ${it.cols()}x${it.rows()} in ${(System.nanoTime() - start) / 1_000_000} ms") }
                ?.also { keep(key, it) }
        } ?: return null
        val out = Mat()
        Imgproc.resize(small, out, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        small.release()
        return out
    }

    /** The network's answer at its own scale: the page at [SIDE] long (smaller pages as they are). */
    private fun run(net: OrtSession, rgb: Mat): Mat {
        val scale = min(1.0, SIDE / max(rgb.cols(), rgb.rows()).toDouble())
        val page = Mat()
        if (scale < 1.0) Imgproc.resize(rgb, page, Size(), scale, scale, Imgproc.INTER_AREA) else rgb.copyTo(page)
        val w = page.cols()
        val h = page.rows()
        val out = Mat(h, w, CvType.CV_8UC1)
        val env = OrtEnvironment.getEnvironment()
        // In tiles: the whole page at once would want hundreds of megabytes. Each tile reaches
        // [MARGIN] past its share, so strokes cut at a tile's edge are judged with what's beside them.
        val step = TILE - 2 * MARGIN
        var y0 = 0
        while (y0 < h) {
            var x0 = 0
            while (x0 < w) {
                val tx = max(0, min(x0 - MARGIN, w - TILE))
                val ty = max(0, min(y0 - MARGIN, h - TILE))
                val tw = min(TILE, w - tx)
                val th = min(TILE, h - ty)
                // Sides a multiple of 16, for the network's four halvings; padded with the edge.
                val pw = (tw + 15) / 16 * 16
                val ph = (th + 15) / 16 * 16
                val tile = Mat(page, Rect(tx, ty, tw, th))
                val padded = Mat()
                org.opencv.core.Core.copyMakeBorder(tile, padded, 0, ph - th, 0, pw - tw, org.opencv.core.Core.BORDER_REPLICATE)
                tile.release()
                val pixels = ByteArray(pw * ph * 3)
                padded.get(0, 0, pixels)
                padded.release()
                val plane = pw * ph
                val input = FloatArray(3 * plane)
                for (i in 0 until plane) {
                    input[i] = (pixels[3 * i].toInt() and 0xFF) / 255f
                    input[plane + i] = (pixels[3 * i + 1].toInt() and 0xFF) / 255f
                    input[2 * plane + i] = (pixels[3 * i + 2].toInt() and 0xFF) / 255f
                }
                val logits = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, ph.toLong(), pw.toLong())).use { tensor ->
                    net.run(mapOf(net.inputNames.first() to tensor)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
                    }
                }
                // Only this tile's own share is written: its margins belong to its neighbours.
                val sx = x0 - tx
                val sy = y0 - ty
                val sw = min(step, w - x0)
                val sh = min(step, h - y0)
                val row = ByteArray(sw)
                for (y in 0 until sh) {
                    val line = logits[sy + y]
                    for (x in 0 until sw) row[x] = (255.0 / (1.0 + exp(-line[sx + x].toDouble()))).roundToInt().toByte()
                    out.put(y0 + y, x0, row)
                }
                x0 += step
            }
            y0 += step
        }
        page.release()
        return out
    }

    /** How the page looks, coarsely: the same page at another size gives the same digest. */
    private fun digest(rgb: Mat): String {
        val tiny = Mat()
        Imgproc.resize(rgb, tiny, Size(DIGEST_W.toDouble(), DIGEST_H.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bytes = ByteArray(DIGEST_W * DIGEST_H * 3)
        tiny.get(0, 0, bytes)
        tiny.release()
        for (i in bytes.indices) bytes[i] = ((bytes[i].toInt() and 0xFF) shr DIGEST_SHIFT).toByte()
        val md = MessageDigest.getInstance("SHA-1")
        md.update(bytes)
        // The shape too: a page cropped differently can look alike squeezed this small.
        md.update((rgb.cols() * 100 / max(1, rgb.rows())).toString().toByteArray())
        md.update(VERSION.toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fromDisk(key: String): Mat? {
        val file = File(cacheDir ?: return null, "$key.png")
        if (!file.exists()) return null
        val m = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_GRAYSCALE)
        if (m.empty()) return null
        synchronized(this) { remember(key, m.clone()) }
        return m
    }

    private fun keep(key: String, small: Mat) {
        synchronized(this) { remember(key, small.clone()) }
        cacheDir?.let { dir ->
            runCatching {
                Imgcodecs.imwrite(File(dir, "$key.png").path, small)
                // The folder holds the last pages worked on; the oldest go.
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(DISK_KEPT)?.forEach { it.delete() }
            }
        }
    }

    private fun remember(key: String, small: Mat) {
        recent.remove(key)?.release()
        recent[key] = small
        while (recent.size > MEMORY_KEPT) {
            val oldest = recent.keys.first()
            recent.remove(oldest)?.release()
        }
    }

    private const val TAG = "HandMask"
    private const val MODEL = "clean/hand.onnx"
    /** Bumped with the model, so answers from an older one aren't reused. */
    private const val VERSION = "3"
    /** The page's long side as the network sees it: writing near the size it was trained on. */
    private const val SIDE = 2200.0
    private const val TILE = 512
    private const val MARGIN = 32
    private const val DIGEST_W = 48
    private const val DIGEST_H = 64
    private const val DIGEST_SHIFT = 3
    private const val MEMORY_KEPT = 4
    private const val DISK_KEPT = 200
}
