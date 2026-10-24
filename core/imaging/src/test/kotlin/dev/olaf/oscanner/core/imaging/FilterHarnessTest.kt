package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.PageFilter
import org.junit.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Runs the looks named in `FILT_LOOKS` (default Auto) on every picture in `FILT_DIR` and writes
 * `<name>_<look>.png` to `FILT_OUT`, for the filter benchmark. Does nothing without `FILT_DIR`.
 */
class FilterHarnessTest {
    @Test
    fun run() {
        nu.pattern.OpenCV.loadLocally()
        val dir = File(System.getenv("FILT_DIR") ?: return)
        val out = File(System.getenv("FILT_OUT") ?: "${dir.path}/out").apply { mkdirs() }
        val looks = (System.getenv("FILT_LOOKS") ?: "Auto").split(',').map { PageFilter.valueOf(it.trim()) }
        dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "png") }!!.sorted().forEach { f ->
            val bgr = Imgcodecs.imread(f.path)
            val rgb = Mat()
            Imgproc.cvtColor(bgr, rgb, Imgproc.COLOR_BGR2RGB)
            bgr.release()
            for (look in looks) {
                val t0 = System.nanoTime()
                val result = Filters.apply(rgb, look)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (result.channels() == 3) Imgproc.cvtColor(result, result, Imgproc.COLOR_RGB2BGR)
                Imgcodecs.imwrite(File(out, "${f.nameWithoutExtension}_$look.png").path, result)
                result.release()
                println("${f.name} $look $ms ms")
            }
            rgb.release()
        }
    }
}
