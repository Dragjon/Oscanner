package dev.olaf.oscanner.core.imaging

import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Runs the detector on every `scene_*.png` in `DET_DIR` and writes `kotlin_results.txt` (see
 * tools/detector-bench). Does nothing without `DET_DIR`. With `DET_MASKS` set to a folder of
 * `<scene>.png` page masks (from the page finder, run beforehand), those stand in for it, and with
 * `DET_CORNERS` a folder of `<scene>.txt` outlines from the corner network (x,y;x,y;x,y;x,y, or
 * `none`) stand in for that. With
 * `DET_SEQ` set, the scenes are frames of moving shots (`scene_<shot>_<frame>.png`), in name
 * order, and each is given where the page was found in the frame before, as the camera does;
 * `kinds.txt` then says for each frame how long it took and whether the page was followed.
 * With `DET_NET_EVERY=k` too, the corner network runs as the camera runs it, on its own thread:
 * every k-th frame gets its outline from k frames before, checked against the frame.
 */
class DetectorHarnessTest {
    @Test
    fun run() {
        nu.pattern.OpenCV.loadLocally()
        val dir = File(System.getenv("DET_DIR") ?: return)
        val masks = System.getenv("DET_MASKS")?.let(::File)
        val cornerDir = System.getenv("DET_CORNERS")?.let(::File)
        val netEvery = System.getenv("DET_NET_EVERY")?.toInt()
        val shotFrames = mutableListOf<String>()
        // The network's last outline, held between its results; dropped after two misses running.
        var held: dev.olaf.oscanner.core.model.Quad? = null
        var misses = 0
        val out = StringBuilder()
        val sequence = System.getenv("DET_SEQ") != null
        var prior: dev.olaf.oscanner.core.model.Quad? = null
        var followed = 0
        var shot = ""
        val kinds = StringBuilder()
        dir.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".png") }!!.sorted().forEach { f ->
            val gray = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val maskFile = masks?.let { File(it, f.name) }?.takeIf(File::exists)
            val finder = maskFile?.let { m -> PageFinder { Imgcodecs.imread(m.path, Imgcodecs.IMREAD_GRAYSCALE) } }
            val cornerFile = cornerDir?.let { File(it, f.name.removeSuffix(".png") + ".txt") }?.takeIf(File::exists)
            fun cornersOf(file: File?) = file?.takeIf(File::exists)?.readText()?.trim()?.takeIf { it != "none" }?.let { text ->
                dev.olaf.oscanner.core.model.Quad.of(text.split(';').map { p -> p.split(',').let { dev.olaf.oscanner.core.model.Corner(it[0].toFloat(), it[1].toFloat()) } })
            }
            val cornerFinder = cornersOf(cornerFile)?.takeIf { netEvery == null }?.let { q -> CornerFinder { q } }
            val d = DocumentDetector(keepSpreads = f.name.contains("spreadkeep"), cards = f.name.startsWith("scene_card"), finder = finder, cornerFinder = cornerFinder)
            // A new shot starts with each sequence, scene_<shot>_<frame>.png.
            val shotName = f.name.substringBeforeLast('_')
            if (sequence && shotName != shot) {
                shot = shotName
                prior = null
                followed = 0
                shotFrames.clear()
                held = null
                misses = 0
            }
            shotFrames += f.name
            val t0 = System.nanoTime()
            val given = prior.takeIf { followed < 10 }
            val i = shotFrames.size - 1
            // The network's outline of frame i - k reaches frame i.
            val late = if (netEvery != null && i >= netEvery && i % netEvery == 0) cornersOf(cornerDir?.let { File(it, shotFrames[i - netEvery].removeSuffix(".png") + ".txt") }) else null
            val resultFrame = netEvery != null && i >= netEvery && i % netEvery == 0
            val checked = late?.let { d.withCorners(gray, it) }
            if (resultFrame) {
                if (checked != null) { held = checked.quad; misses = 0 } else if (++misses >= 2) held = null
            }
            fun once() = checked ?: held?.let { Detection(it, 0.0) } ?: d.detect(gray, given)
            var q = once().quad
            repeat(4) { q = once().quad }
            if (sequence) {
                // As the camera does: a found page is followed for up to 10 frames without being
                // found afresh.
                val ts = System.nanoTime()
                val detection = once()
                val one = (System.nanoTime() - ts) / 1e6
                followed = if (detection.quad != null && detection.followed) followed + 1 else 0
                kinds.append(f.name).append(' ').append(String.format("%.2f", one)).append(' ').append(if (detection.followed) "followed" else "search").append('\n')
                prior = detection.quad
            }
            val ms = (System.nanoTime() - t0) / 5e6
            out.append(f.name).append(' ').append(String.format("%.1f", ms)).append(' ')
            out.append(q?.corners?.joinToString(";") { "${it.x},${it.y}" } ?: "none").append('\n')
            d.release()
        }
        File(dir, "kotlin_results.txt").writeText(out.toString())
        if (sequence) File(dir, "kinds.txt").writeText(kinds.toString())
    }
}
