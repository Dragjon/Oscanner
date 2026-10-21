package dev.olaf.oscanner.core.imaging

import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Runs the detector on every `scene_*.png` in `DET_DIR` and writes `kotlin_results.txt` (see
 * tools/detector-bench). Does nothing without `DET_DIR`. With `DET_MASKS` set to a folder of
 * `<scene>.png` page masks (from the page finder, run beforehand), those stand in for it. With
 * `DET_SEQ` set, the scenes are frames of one moving shot, in name order, and each is given
 * where the page was found in the frame before, as the camera does.
 */
class DetectorHarnessTest {
    @Test
    fun run() {
        nu.pattern.OpenCV.loadLocally()
        val dir = File(System.getenv("DET_DIR") ?: return)
        val masks = System.getenv("DET_MASKS")?.let(::File)
        val out = StringBuilder()
        val sequence = System.getenv("DET_SEQ") != null
        var prior: dev.olaf.oscanner.core.model.Quad? = null
        var followed = 0
        dir.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".png") }!!.sorted().forEach { f ->
            val gray = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val maskFile = masks?.let { File(it, f.name) }?.takeIf(File::exists)
            val finder = maskFile?.let { m -> PageFinder { Imgcodecs.imread(m.path, Imgcodecs.IMREAD_GRAYSCALE) } }
            val d = DocumentDetector(keepSpreads = f.name.contains("spreadkeep"), finder = finder)
            val t0 = System.nanoTime()
            val given = prior.takeIf { followed < 10 }
            var q = d.detect(gray, given).quad
            repeat(4) { q = d.detect(gray, given).quad }
            if (sequence) {
                // As the camera does: a found page is followed for up to 10 frames without being
                // found afresh. Found afresh means found with no prior given.
                val fresh = given == null || d.detect(gray).quad != null
                followed = if (q == null) 0 else if (fresh) 0 else followed + 1
                prior = q
            }
            val ms = (System.nanoTime() - t0) / 5e6
            out.append(f.name).append(' ').append(String.format("%.1f", ms)).append(' ')
            out.append(q?.corners?.joinToString(";") { "${it.x},${it.y}" } ?: "none").append('\n')
            d.release()
        }
        File(dir, "kotlin_results.txt").writeText(out.toString())
    }
}
