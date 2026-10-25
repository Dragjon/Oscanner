package dev.olaf.oscanner.core.imaging

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Fills a hole in a picture with pieces of the rest of it, so grass goes on as grass and sky as
 * sky: the way a torn-off corner of a photo is mended. Smearing in from the edges, as dust is
 * filled, turns anything bigger than a speck into a blur.
 *
 * Each small square around the hole is matched to the most alike square from the whole picture
 * (PatchMatch: a match is passed on to the neighbors and tried at random further out), and each
 * hole pixel becomes the blend of what the squares over it say, weighted by how well they
 * matched. Done from a small copy up to the working size, each size starting from the last
 * (Wexler, Shechtman and Irani, "Space-Time Completion of Video", 2007; Barnes et al.,
 * "PatchMatch", 2009).
 */
internal object PatchFill {

    /** Fills where [mask] (8-bit, nonzero = hole) is set in [rgb] (8-bit RGB), in place. */
    fun fill(rgb: Mat, mask: Mat) {
        if (Core.countNonZero(mask) == 0) return
        val scale = min(1.0, WORK_SIDE.toDouble() / max(rgb.cols(), rgb.rows()))
        val work = Mat()
        val workMask = Mat()
        Imgproc.resize(rgb, work, Size(), scale, scale, Imgproc.INTER_AREA)
        Imgproc.resize(mask, workMask, work.size(), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.threshold(workMask, workMask, 0.0, 255.0, Imgproc.THRESH_BINARY)
        val filled = complete(work, workMask)
        work.release()
        workMask.release()
        // Back at full size only the hole is taken from the fill, its edge blended over a few pixels.
        val big = Mat()
        Imgproc.resize(filled, big, rgb.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        filled.release()
        val soft = Mat()
        Imgproc.dilate(mask, soft, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
        Imgproc.GaussianBlur(soft, soft, Size(5.0, 5.0), 0.0)
        Core.max(soft, mask, soft)
        blend(rgb, big, soft)
        listOf(big, soft).forEach(Mat::release)
    }

    /** [rgb] with [hole] filled, as a new Mat the same size. */
    private fun complete(rgb: Mat, hole: Mat): Mat {
        // Smaller and smaller copies, down to where the hole is a few squares across, while the
        // short side still holds a few squares: a strip shrunk further is all hole.
        val images = mutableListOf(rgb.clone())
        val holes = mutableListOf(hole.clone())
        while (max(images.last().cols(), images.last().rows()) / 2 >= COARSEST && min(images.last().cols(), images.last().rows()) / 2 >= PATCH * 4) {
            val img = Mat()
            val h = Mat()
            Imgproc.resize(images.last(), img, Size(), 0.5, 0.5, Imgproc.INTER_AREA)
            Imgproc.resize(holes.last(), h, img.size(), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.threshold(h, h, 0.0, 255.0, Imgproc.THRESH_BINARY)
            images += img
            holes += h
        }
        // The smallest starts smeared in; every size after it starts from the one before.
        val coarsest = images.last()
        Photo.inpaint(coarsest, holes.last(), coarsest, 3.0, Photo.INPAINT_TELEA)
        var level = Level(coarsest, holes.last(), Random(1))
        level.match(null)
        repeat(COARSE_ROUNDS) { level.round() }
        for (i in images.size - 2 downTo 0) {
            val up = Mat()
            Imgproc.resize(level.toMat(), up, images[i].size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            up.copyTo(images[i], holes[i])
            up.release()
            val finer = Level(images[i], holes[i], Random(i + 2))
            finer.match(level)
            finer.vote()
            repeat(if (i == 0) FINE_ROUNDS else ROUNDS) { finer.round() }
            level = finer
        }
        val out = level.toMat()
        (images + holes).forEach(Mat::release)
        return out
    }

    /** One size of the picture: its pixels, its hole, and each hole square's best match. */
    private class Level(image: Mat, hole: Mat, val random: Random) {
        val w = image.cols()
        val h = image.rows()
        val px = FloatArray(w * h * 3)
        val inHole = BooleanArray(w * h)
        /** Squares wholly outside the hole and inside the picture: where matches come from. */
        val source = BooleanArray(w * h)
        val sources: IntArray
        /** Squares that cover some of the hole, by center, and their matches' centers. */
        val targets: IntArray
        val match: IntArray
        val cost: FloatArray
        val index = IntArray(w * h) { -1 }

        init {
            val bytes = ByteArray(w * h * 3)
            image.get(0, 0, bytes)
            for (i in bytes.indices) px[i] = (bytes[i].toInt() and 255).toFloat()
            val holeBytes = ByteArray(w * h)
            hole.get(0, 0, holeBytes)
            for (i in holeBytes.indices) inHole[i] = holeBytes[i] != 0.toByte()
            // A source square has none of the hole in it: the hole grown by a square's reach.
            val grown = Mat()
            Imgproc.dilate(hole, grown, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(PATCH.toDouble(), PATCH.toDouble())))
            val grownBytes = ByteArray(w * h)
            grown.get(0, 0, grownBytes)
            grown.release()
            val s = ArrayList<Int>()
            val t = ArrayList<Int>()
            for (y in R until h - R) for (x in R until w - R) {
                val p = y * w + x
                if (grownBytes[p] == 0.toByte()) {
                    source[p] = true
                    s += p
                } else {
                    index[p] = t.size
                    t += p
                }
            }
            sources = s.toIntArray()
            targets = t.toIntArray()
            match = IntArray(targets.size)
            cost = FloatArray(targets.size)
        }

        /** Starting matches: from [coarser]'s, scaled up, or at random. */
        fun match(coarser: Level?) {
            if (sources.isEmpty()) return
            for (k in targets.indices) {
                val p = targets[k]
                val x = p % w
                val y = p / w
                var m = -1
                if (coarser != null) {
                    val cx = (x / 2).coerceIn(0, coarser.w - 1)
                    val cy = (y / 2).coerceIn(0, coarser.h - 1)
                    val ck = coarser.index[cy * coarser.w + cx]
                    if (ck >= 0) {
                        val cm = coarser.match[ck]
                        val sx = (cm % coarser.w) * 2 + x % 2
                        val sy = (cm / coarser.w) * 2 + y % 2
                        if (sx in 0 until w && sy in 0 until h && source[sy * w + sx]) m = sy * w + sx
                    }
                }
                if (m < 0) m = sources[random.nextInt(sources.size)]
                match[k] = m
                cost[k] = distance(p, m, Float.MAX_VALUE)
            }
        }

        /** Better matches, then the hole made again from them. */
        fun round() {
            if (sources.isEmpty()) return
            search(forward = true)
            search(forward = false)
            vote()
        }

        /** Sum of squared differences between the squares around [a] and [b], given up past [limit]. */
        fun distance(a: Int, b: Int, limit: Float): Float {
            val ax = a % w - R
            val ay = a / w - R
            val bx = b % w - R
            val by = b / w - R
            var sum = 0f
            for (dy in 0 until PATCH) {
                var ia = ((ay + dy) * w + ax) * 3
                var ib = ((by + dy) * w + bx) * 3
                for (dx in 0 until PATCH * 3) {
                    val d = px[ia++] - px[ib++]
                    sum += d * d
                }
                if (sum > limit) return sum
            }
            return sum
        }

        private fun search(forward: Boolean) {
            val step = if (forward) 1 else -1
            val order = if (forward) targets.indices else targets.indices.reversed()
            for (k in order) {
                val p = targets[k]
                var best = match[k]
                var bestCost = cost[k]
                // A neighbor's match, moved along by one: alike pictures run on alike.
                for ((nx, ny) in arrayOf(-step to 0, 0 to -step)) {
                    val q = p + ny * w + nx
                    val qk = if (q in 0 until w * h) index[q] else -1
                    if (qk < 0) continue
                    val c = match[qk] - ny * w - nx
                    if (c !in 0 until w * h || !source[c] || c == best) continue
                    val d = distance(p, c, bestCost)
                    if (d < bestCost) {
                        best = c
                        bestCost = d
                    }
                }
                // And tries at random around the best, nearer and nearer.
                var radius = max(w, h)
                val bx = best % w
                val by = best / w
                while (radius >= 1) {
                    val x = bx + random.nextInt(-radius, radius + 1)
                    val y = by + random.nextInt(-radius, radius + 1)
                    if (x in 0 until w && y in 0 until h) {
                        val c = y * w + x
                        if (source[c]) {
                            val d = distance(p, c, bestCost)
                            if (d < bestCost) {
                                best = c
                                bestCost = d
                            }
                        }
                    }
                    radius /= 2
                }
                match[k] = best
                cost[k] = bestCost
            }
        }

        /** Each hole pixel: what the squares over it say, the better matched counting more. */
        fun vote() {
            if (sources.isEmpty()) return
            val typical = cost.copyOf().also { it.sort() }.let { it[it.size / 2] }.coerceAtLeast(1f)
            val sum = FloatArray(w * h * 3)
            val weight = FloatArray(w * h)
            for (k in targets.indices) {
                val p = targets[k]
                val m = match[k]
                val wt = exp(-cost[k] / (2f * typical))
                val px0 = p % w - R
                val py0 = p / w - R
                val mx0 = m % w - R
                val my0 = m / w - R
                for (dy in 0 until PATCH) for (dx in 0 until PATCH) {
                    val q = (py0 + dy) * w + px0 + dx
                    if (!inHole[q]) continue
                    val s = ((my0 + dy) * w + mx0 + dx) * 3
                    sum[q * 3] += wt * px[s]
                    sum[q * 3 + 1] += wt * px[s + 1]
                    sum[q * 3 + 2] += wt * px[s + 2]
                    weight[q] += wt
                }
            }
            for (q in 0 until w * h) {
                if (!inHole[q] || weight[q] <= 0f) continue
                px[q * 3] = sum[q * 3] / weight[q]
                px[q * 3 + 1] = sum[q * 3 + 1] / weight[q]
                px[q * 3 + 2] = sum[q * 3 + 2] / weight[q]
            }
            for (k in targets.indices) cost[k] = distance(targets[k], match[k], Float.MAX_VALUE)
        }

        fun toMat(): Mat {
            val m = Mat(h, w, CvType.CV_8UC3)
            m.put(0, 0, ByteArray(px.size) { px[it].toInt().coerceIn(0, 255).toByte() })
            return m
        }
    }

    /** [into] = [from] where [alpha] is 255, mixed where it's between, kept where it's 0. */
    private fun blend(into: Mat, from: Mat, alpha: Mat) {
        val a = Mat()
        alpha.convertTo(a, CvType.CV_32F, 1.0 / 255)
        val a3 = Mat()
        Core.merge(listOf(a, a, a), a3)
        val f = Mat()
        val t = Mat()
        from.convertTo(f, CvType.CV_32FC3)
        into.convertTo(t, CvType.CV_32FC3)
        Core.subtract(f, t, f)
        Core.multiply(f, a3, f)
        Core.add(t, f, t)
        t.convertTo(into, CvType.CV_8UC3)
        listOf(a, a3, f, t).forEach(Mat::release)
    }

    /** Squares are this many pixels on a side. */
    private const val PATCH = 7
    private const val R = PATCH / 2
    /** The fill is worked out at most this big, and scaled up after. */
    private const val WORK_SIDE = 1024
    /** The smallest copy's long side. */
    private const val COARSEST = 48
    private const val COARSE_ROUNDS = 8
    private const val ROUNDS = 4
    private const val FINE_ROUNDS = 2
}
