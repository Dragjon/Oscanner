package dev.olaf.oscanner.core.imaging

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.DMatch
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.SIFT
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Joins overlapping photos of one flat thing too big for a single shot (a poster, a plan, a
 * wall chart) into one picture. A flat thing seen from anywhere is the same picture under a
 * perspective transform, so each shot is placed by the one transform that carries the marks it
 * shares with a shot already placed onto them (matched by their look, SIFT, and kept only where
 * most of them agree, RANSAC: the method of the panorama papers of the 2000s). The shots are
 * then laid on the middle one's plane, each pixel taken from the shot it lies furthest inside,
 * so overlaps meet halfway with no ghosting, and each shot's brightness is matched to what's
 * already down where they overlap.
 */
object PageStitcher {

    /**
     * Where each of [count] shots goes: a 3×3 transform from its pixels (at [MATCH_SIDE] on its
     * long side) onto the plane of the shot all are laid on, or null if some shot overlaps none
     * of the others enough to place it. [load] gives shot i scaled to a long side.
     */
    fun place(count: Int, load: (index: Int, side: Int) -> Mat): Placement? {
        require(count >= 2)
        val shots = (0 until count).map { i -> features(load(i, MATCH_SIDE)) }
        try {
            val toFirst = arrayOfNulls<Mat>(count)
            toFirst[0] = Mat.eye(3, 3, CvType.CV_64F)
            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE)
            // Each pair is matched once, when first wanted.
            val pairs = HashMap<Pair<Int, Int>, Pair<Mat, Int>?>()
            // Each round places the unplaced shot that overlaps a placed one best.
            while (toFirst.any { it == null }) {
                var best: Triple<Int, Mat, Int>? = null
                for (i in 0 until count) {
                    if (toFirst[i] != null) continue
                    for (j in 0 until count) {
                        val onto = toFirst[j] ?: continue
                        val (h, agree) = pairs.getOrPut(i to j) { transform(shots[i], shots[j], matcher) } ?: continue
                        if (best == null || agree > best.third) {
                            best?.second?.release()
                            val chained = Mat()
                            Core.gemm(onto, h, 1.0, Mat(), 0.0, chained)
                            best = Triple(i, chained, agree)
                        }
                    }
                }
                val (i, h, _) = best ?: return null.also {
                    toFirst.forEach { it?.release() }
                    pairs.values.forEach { it?.first?.release() }
                }
                toFirst[i] = h
            }
            pairs.values.forEach { it?.first?.release() }
            // Laid on the shot nearest the middle of them all, so none is stretched far.
            val centres = (0 until count).map { i -> map(toFirst[i]!!, shots[i].width / 2.0, shots[i].height / 2.0) }
            val mx = centres.sumOf { it.x } / count
            val my = centres.sumOf { it.y } / count
            val middle = centres.indices.minBy { (centres[it].x - mx) * (centres[it].x - mx) + (centres[it].y - my) * (centres[it].y - my) }
            val back = toFirst[middle]!!.inv()
            val onMiddle = toFirst.map { h -> Mat().also { Core.gemm(back, h!!, 1.0, Mat(), 0.0, it) } }
            toFirst.forEach { it?.release() }
            back.release()
            val placement = Placement(onMiddle, shots.map { Size(it.width.toDouble(), it.height.toDouble()) }, middle)
            if (!placement.sane()) {
                placement.release()
                return null
            }
            return placement
        } finally {
            shots.forEach { it.release() }
        }
    }

    /**
     * The shots joined, as RGB, or null if they couldn't all be placed. Each is loaded twice:
     * small to place it, then at [side] to draw it. The result is at most [maxPixels].
     */
    fun stitch(count: Int, side: Int = FULL_SIDE, maxPixels: Double = MAX_PIXELS, load: (index: Int, side: Int) -> Mat): Mat? {
        val placement = place(count, load) ?: return null
        try {
            // From the small shots' pixels to the drawn ones', on the middle shot's scale.
            val scale = side.toDouble() / MATCH_SIDE
            val corners = (0 until count).flatMap { i ->
                val s = placement.sizes[i]
                listOf(Point(0.0, 0.0), Point(s.width, 0.0), Point(s.width, s.height), Point(0.0, s.height)).map { map(placement.transforms[i], it.x, it.y) }
            }
            val left = corners.minOf { it.x }
            val top = corners.minOf { it.y }
            var outScale = scale
            val area = (corners.maxOf { it.x } - left) * (corners.maxOf { it.y } - top) * scale * scale
            if (area > maxPixels) outScale *= sqrt(maxPixels / area)
            val width = ceil((corners.maxOf { it.x } - left) * outScale).toInt()
            val height = ceil((corners.maxOf { it.y } - top) * outScale).toInt()
            val canvas = Mat.zeros(height, width, CvType.CV_8UC3)
            // How far inside its shot each pixel down so far lies: the furthest inside wins.
            val depth = Mat.zeros(height, width, CvType.CV_16UC1)
            val shift = Mat.eye(3, 3, CvType.CV_64F).apply {
                put(0, 0, outScale); put(1, 1, outScale); put(0, 2, -left * outScale); put(1, 2, -top * outScale)
            }
            for (i in 0 until count) {
                val shot = rgb(load(i, side))
                // The shot's own pixels onto the small shot's, then on into the picture.
                val down = Mat.eye(3, 3, CvType.CV_64F).apply {
                    val f = placement.sizes[i].width / shot.cols()
                    put(0, 0, f); put(1, 1, f)
                }
                val h = Mat()
                Core.gemm(placement.transforms[i], down, 1.0, Mat(), 0.0, h)
                Core.gemm(shift, h, 1.0, Mat(), 0.0, h)
                lay(shot, h, canvas, depth)
                listOf(shot, down, h).forEach(Mat::release)
            }
            fillBare(canvas, depth)
            listOf(depth, shift).forEach(Mat::release)
            return canvas
        } finally {
            placement.release()
        }
    }

    /** Where the shots go; see [place]. */
    class Placement(val transforms: List<Mat>, val sizes: List<Size>, val middle: Int) {
        /** Every shot still a fair four-sided shape where it lands, not folded or blown up. */
        internal fun sane(): Boolean = transforms.indices.all { i ->
            val s = sizes[i]
            val c = listOf(Point(0.0, 0.0), Point(s.width, 0.0), Point(s.width, s.height), Point(0.0, s.height)).map { map(transforms[i], it.x, it.y) }
            val crosses = (0 until 4).map { k ->
                val a = c[k]
                val b = c[(k + 1) % 4]
                val d = c[(k + 2) % 4]
                (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            }
            val area = abs((0 until 4).sumOf { k -> c[k].x * c[(k + 1) % 4].y - c[(k + 1) % 4].x * c[k].y }) / 2
            val ratio = area / (s.width * s.height)
            (crosses.all { it > 0 } || crosses.all { it < 0 }) && ratio in MIN_SCALE..MAX_SCALE
        }

        fun release() = transforms.forEach(Mat::release)
    }

    private class Shot(val keypoints: MatOfKeyPoint, val descriptors: Mat, val width: Int, val height: Int) {
        fun release() {
            keypoints.release()
            descriptors.release()
        }
    }

    private fun features(image: Mat): Shot {
        val gray = Mat()
        when (image.channels()) {
            1 -> image.copyTo(gray)
            4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
            else -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
        }
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        SIFT.create(FEATURES).detectAndCompute(gray, Mat(), keypoints, descriptors)
        val shot = Shot(keypoints, descriptors, image.cols(), image.rows())
        listOf(gray, image).forEach(Mat::release)
        return shot
    }

    /**
     * The transform carrying [from]'s marks onto [onto]'s, and how many marks agree with it; null
     * if too few do. A mark counts only if its best match is clearly better than its second
     * (Lowe's ratio test): print repeats letters everywhere, and a near tie is a guess.
     */
    private fun transform(from: Shot, onto: Shot, matcher: DescriptorMatcher): Pair<Mat, Int>? {
        if (from.descriptors.empty() || onto.descriptors.empty()) return null
        val pairs = mutableListOf<MatOfDMatch>()
        matcher.knnMatch(from.descriptors, onto.descriptors, pairs, 2)
        val good = pairs.mapNotNull { m ->
            val two = m.toArray()
            m.release()
            two.takeIf { it.size == 2 && it[0].distance < RATIO * it[1].distance }?.get(0)
        }
        if (good.size < MIN_AGREE) return null
        val a = from.keypoints.toArray()
        val b = onto.keypoints.toArray()
        val src = MatOfPoint2f(*good.map { a[it.queryIdx].pt }.toTypedArray())
        val dst = MatOfPoint2f(*good.map { b[it.trainIdx].pt }.toTypedArray())
        val inliers = Mat()
        val h = Calib3d.findHomography(src, dst, Calib3d.RANSAC, REPROJECTION, inliers)
        val agree = if (inliers.empty()) 0 else Core.countNonZero(inliers)
        listOf(src, dst, inliers).forEach(Mat::release)
        if (h.empty() || agree < MIN_AGREE || agree < AGREE_SHARE * good.size) {
            h.release()
            return null
        }
        return h to agree
    }

    /** [shot] laid onto [canvas] through [h], where it lies further inside itself than what's there ([depth]). */
    private fun lay(shot: Mat, h: Mat, canvas: Mat, depth: Mat) {
        // Only the part of the picture the shot covers is worked on.
        val c = listOf(Point(0.0, 0.0), Point(shot.cols().toDouble(), 0.0), Point(shot.cols().toDouble(), shot.rows().toDouble()), Point(0.0, shot.rows().toDouble())).map { map(h, it.x, it.y) }
        val x0 = max(0, floor(c.minOf { it.x }).toInt())
        val y0 = max(0, floor(c.minOf { it.y }).toInt())
        val x1 = min(canvas.cols(), ceil(c.maxOf { it.x }).toInt())
        val y1 = min(canvas.rows(), ceil(c.maxOf { it.y }).toInt())
        if (x1 <= x0 || y1 <= y0) return
        val box = Rect(x0, y0, x1 - x0, y1 - y0)
        val toBox = h.clone()
        val moved = Mat.eye(3, 3, CvType.CV_64F).apply { put(0, 2, -x0.toDouble()); put(1, 2, -y0.toDouble()) }
        Core.gemm(moved, h, 1.0, Mat(), 0.0, toBox)
        // How far each pixel lies inside its shot: 1 at the edge.
        val inside = Mat(shot.rows(), shot.cols(), CvType.CV_8UC1, Scalar(255.0))
        Imgproc.rectangle(inside, Point(0.0, 0.0), Point(shot.cols() - 1.0, shot.rows() - 1.0), Scalar(0.0), 1)
        val dist = Mat()
        Imgproc.distanceTransform(inside, dist, Imgproc.DIST_L2, 3)
        Core.add(dist, Scalar(1.0), dist)
        dist.convertTo(dist, CvType.CV_16UC1)
        val warped = Mat()
        val warpedDepth = Mat()
        Imgproc.warpPerspective(shot, warped, toBox, box.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0))
        Imgproc.warpPerspective(dist, warpedDepth, toBox, box.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        val canvasBox = canvas.submat(box)
        val depthBox = depth.submat(box)
        // Brightness matched to what's already down where they overlap.
        val both = Mat()
        val laid = Mat()
        Core.compare(depthBox, Scalar(0.0), laid, Core.CMP_GT)
        Core.compare(warpedDepth, Scalar(0.0), both, Core.CMP_GT)
        Core.bitwise_and(both, laid, both)
        if (Core.countNonZero(both) > MIN_OVERLAP) {
            val there = Core.mean(canvasBox, both)
            val here = Core.mean(warped, both)
            val gain = Scalar(DoubleArray(3) { k -> (there.`val`[k] / max(here.`val`[k], 1.0)).coerceIn(MIN_GAIN, MAX_GAIN) })
            Core.multiply(warped, gain, warped)
        }
        val wins = Mat()
        Core.compare(warpedDepth, depthBox, wins, Core.CMP_GT)
        warped.copyTo(canvasBox, wins)
        warpedDepth.copyTo(depthBox, wins)
        listOf(toBox, moved, inside, dist, warped, warpedDepth, canvasBox, depthBox, both, laid, wins).forEach(Mat::release)
    }

    /** What no shot covers, in the color of what's laid down beside it (most often desk or wall). */
    private fun fillBare(canvas: Mat, depth: Mat) {
        val bare = Mat()
        Core.compare(depth, Scalar(0.0), bare, Core.CMP_EQ)
        if (Core.countNonZero(bare) > 0) {
            val near = Mat()
            val reach = (max(canvas.cols(), canvas.rows()) * BARE_REACH).coerceAtLeast(3.0)
            Imgproc.dilate(bare, near, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(reach, reach)))
            Core.bitwise_xor(near, bare, near)
            if (Core.countNonZero(near) > 0) canvas.setTo(Core.mean(canvas, near), bare)
            near.release()
        }
        bare.release()
    }

    private fun rgb(image: Mat): Mat {
        if (image.channels() == 3) return image
        val out = Mat()
        Imgproc.cvtColor(image, out, if (image.channels() == 4) Imgproc.COLOR_RGBA2RGB else Imgproc.COLOR_GRAY2RGB)
        image.release()
        return out
    }

    internal fun map(h: Mat, x: Double, y: Double): Point {
        val m = DoubleArray(9).also { h.get(0, 0, it) }
        val w = m[6] * x + m[7] * y + m[8]
        return Point((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** Shots are matched at this long side. */
    const val MATCH_SIDE = 1200
    /** ...and drawn at this one. */
    const val FULL_SIDE = 3000
    /** The joined picture is at most this many pixels; bigger, it's scaled down. */
    const val MAX_PIXELS = 24e6
    private const val FEATURES = 4000
    private const val RATIO = 0.75
    private const val REPROJECTION = 4.0
    /** Two shots overlap if this many marks agree on where one goes on the other, and this share of those matched. */
    private const val MIN_AGREE = 30
    private const val AGREE_SHARE = 0.05
    /** A shot lands between these shares of its own size: else the transform is wrong. */
    private const val MIN_SCALE = 0.15
    private const val MAX_SCALE = 6.0
    private const val MIN_OVERLAP = 500
    private const val MIN_GAIN = 0.7
    private const val MAX_GAIN = 1.4
    /** Bare corners take the color of what's laid within this share of the picture's side. */
    private const val BARE_REACH = 0.02
}
