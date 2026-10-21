package dev.olaf.oscanner.core.imaging

import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * Runs the detector on every `scene_*.png` in `DET_DIR` and writes `kotlin_results.txt` (see
 * tools/detector-bench). Does nothing without `DET_DIR`. With `DET_MASKS` set to a folder of
 * `<scene>.png` page masks (from the page finder, run beforehand), those stand in for it.
 */
class DetectorHarnessTest {
    @Test
    fun run() {
        nu.pattern.OpenCV.loadLocally()
        val dir = File(System.getenv("DET_DIR") ?: return)
        val masks = System.getenv("DET_MASKS")?.let(::File)
        val out = StringBuilder()
        dir.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".png") }!!.sorted().forEach { f ->
            val gray = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val maskFile = masks?.let { File(it, f.name) }?.takeIf(File::exists)
            val finder = maskFile?.let { m -> PageFinder { Imgcodecs.imread(m.path, Imgcodecs.IMREAD_GRAYSCALE) } }
            val d = DocumentDetector(keepSpreads = f.name.contains("spreadkeep"), finder = finder)
            val t0 = System.nanoTime()
            var q = d.detect(gray).quad
            repeat(4) { q = d.detect(gray).quad }
            val ms = (System.nanoTime() - t0) / 5e6
            out.append(f.name).append(' ').append(String.format("%.1f", ms)).append(' ')
            out.append(q?.corners?.joinToString(";") { "${it.x},${it.y}" } ?: "none").append('\n')
            d.release()
        }
        File(dir, "kotlin_results.txt").writeText(out.toString())
    }
}
