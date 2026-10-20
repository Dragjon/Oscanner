package dev.olaf.oscanner.core.imaging

import org.junit.Test
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

class DetectorHarnessTest {
    @Test
    fun run() {
        nu.pattern.OpenCV.loadLocally()
        val dir = File(System.getenv("DET_DIR") ?: return)
        val out = StringBuilder()
        dir.listFiles { f -> f.name.startsWith("scene_") && f.name.endsWith(".png") }!!.sorted().forEach { f ->
            val gray = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val spreads = f.name.contains("_book")
            val d = DocumentDetector(keepSpreads = f.name.contains("spreadkeep"))
            val t0 = System.nanoTime()
            var q = d.detect(gray).quad
            repeat(4) { q = d.detect(gray).quad }
            val ms = (System.nanoTime() - t0) / 5e6
            out.append(f.name).append(' ').append(String.format("%.1f", ms)).append(' ')
            out.append(q?.corners?.joinToString(";") { "${it.x},${it.y}" } ?: "none").append('\n')
            d.release()
            spreads.hashCode()
        }
        File(dir, "kotlin_results.txt").writeText(out.toString())
    }
}
