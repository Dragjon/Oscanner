package dev.olaf.oscanner.core.imaging

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.Quad
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** Plain geometry on quads and convex polygons. No OpenCV, so it runs in JVM unit tests. */
object QuadGeometry {

    /**
     * Puts four points in clockwise order starting at the top-left, in a y-down image.
     * Sorts by angle around the centroid, then starts from the point with the smallest x + y.
     */
    fun order(points: List<Corner>): Quad {
        require(points.size == 4)
        val cx = points.sumOf { it.x.toDouble() } / 4
        val cy = points.sumOf { it.y.toDouble() } / 4
        // atan2 with y down grows clockwise on screen.
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return Quad.of(List(4) { sorted[(start + it) % 4] })
    }

    /** Shoelace area; positive for clockwise polygons in y-down coordinates. */
    fun signedArea(polygon: List<Corner>): Double {
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return sum / 2
    }

    fun area(polygon: List<Corner>): Double = abs(signedArea(polygon))

    fun isConvex(quad: Quad): Boolean {
        val c = quad.corners
        var sign = 0
        for (i in 0 until 4) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            val cross = (b.x - a.x) * (d.y - b.y) - (b.y - a.y) * (d.x - b.x)
            if (abs(cross) < 1e-9f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Interior angles in degrees, one per corner in quad order. */
    fun angles(quad: Quad): List<Double> {
        val c = quad.corners
        return List(4) { i ->
            val p = c[i]
            val prev = c[(i + 3) % 4]
            val next = c[(i + 1) % 4]
            val ax = prev.x - p.x
            val ay = prev.y - p.y
            val bx = next.x - p.x
            val by = next.y - p.y
            val denom = hypot(ax, ay) * hypot(bx, by)
            if (denom == 0f) 0.0 else Math.toDegrees(acos(((ax * bx + ay * by) / denom).toDouble().coerceIn(-1.0, 1.0)))
        }
    }

    /** Intersection over union of two convex quads. 1 means identical, 0 means disjoint. */
    fun iou(a: Quad, b: Quad): Double {
        val areaA = area(a.corners)
        val areaB = area(b.corners)
        if (areaA == 0.0 || areaB == 0.0) return 0.0
        val inter = area(clip(a.corners, b.corners))
        val union = areaA + areaB - inter
        return if (union <= 0.0) 0.0 else inter / union
    }

    /** Sutherland–Hodgman: [subject] clipped by the convex [clipper]. Both clockwise. */
    private fun clip(subject: List<Corner>, clipper: List<Corner>): List<Corner> {
        var output = subject
        val orientation = if (signedArea(clipper) >= 0) 1f else -1f
        for (i in clipper.indices) {
            if (output.isEmpty()) break
            val e1 = clipper[i]
            val e2 = clipper[(i + 1) % clipper.size]
            fun inside(p: Corner) = orientation * ((e2.x - e1.x) * (p.y - e1.y) - (e2.y - e1.y) * (p.x - e1.x)) >= 0f
            val input = output
            output = buildList {
                for (j in input.indices) {
                    val cur = input[j]
                    val prev = input[(j + input.size - 1) % input.size]
                    val curIn = inside(cur)
                    val prevIn = inside(prev)
                    if (curIn) {
                        if (!prevIn) add(intersect(prev, cur, e1, e2))
                        add(cur)
                    } else if (prevIn) {
                        add(intersect(prev, cur, e1, e2))
                    }
                }
            }
        }
        return output
    }

    private fun intersect(p1: Corner, p2: Corner, q1: Corner, q2: Corner): Corner {
        val a1 = p2.y - p1.y
        val b1 = p1.x - p2.x
        val c1 = a1 * p1.x + b1 * p1.y
        val a2 = q2.y - q1.y
        val b2 = q1.x - q2.x
        val c2 = a2 * q1.x + b2 * q1.y
        val det = a1 * b2 - a2 * b1
        if (abs(det) < 1e-12f) return p2
        return Corner((b2 * c1 - b1 * c2) / det, (a1 * c2 - a2 * c1) / det)
    }

    /**
     * Maps a quad in normalized coordinates of a buffer to the same quad after the buffer is
     * turned [degrees] clockwise (camera sensor buffers arrive sideways).
     */
    fun rotate(quad: Quad, degrees: Int): Quad {
        val turn: (Corner) -> Corner = when (((degrees % 360) + 360) % 360) {
            90 -> { c -> Corner(1f - c.y, c.x) }
            180 -> { c -> Corner(1f - c.x, 1f - c.y) }
            270 -> { c -> Corner(c.y, 1f - c.x) }
            else -> { c -> c }
        }
        return order(quad.corners.map(turn))
    }

    /** Blends [from] toward [to] corner by corner; [alpha] 1 jumps straight to [to]. */
    /** The furthest any corner of [a] is from the matching corner of [b]. */
    fun maxCornerDistance(a: Quad, b: Quad): Float =
        a.corners.zip(b.corners).maxOf { (p, q) -> kotlin.math.hypot(p.x - q.x, p.y - q.y) }

    fun lerp(from: Quad, to: Quad, alpha: Float): Quad = Quad.of(
        from.corners.zip(to.corners) { a, b -> Corner(a.x + (b.x - a.x) * alpha, a.y + (b.y - a.y) * alpha) },
    )

    /** Left and right halves, cut between the midpoints of the top and bottom edges. */
    fun splitInHalf(quad: Quad): Pair<Quad, Quad> {
        fun mid(a: Corner, b: Corner) = Corner((a.x + b.x) / 2, (a.y + b.y) / 2)
        val top = mid(quad.topLeft, quad.topRight)
        val bottom = mid(quad.bottomLeft, quad.bottomRight)
        return Quad(quad.topLeft, top, bottom, quad.bottomLeft) to Quad(top, quad.topRight, quad.bottomRight, bottom)
    }

    /**
     * Where a point on the page as shown (after straightening by [quad] and turning
     * [rotation] degrees clockwise) sits in the photo, both normalized. This is the exact
     * perspective map the renderer warps with: scaling either side to pixels doesn't change it.
     */
    fun pageToSource(quad: Quad, rotation: Int, point: Corner): Corner {
        val (u, v) = unturn(point, rotation)
        return Homography.squareTo(quad).map(u, v)
    }

    /** The inverse of [pageToSource]. */
    fun sourceToPage(quad: Quad, rotation: Int, point: Corner): Corner {
        val p = Homography.squareTo(quad).inverse().map(point.x.toDouble(), point.y.toDouble())
        return turn(p, rotation)
    }

    private fun unturn(c: Corner, rotation: Int): Pair<Double, Double> {
        val x = c.x.toDouble()
        val y = c.y.toDouble()
        return when (((rotation % 360) + 360) % 360) {
            90 -> y to 1 - x
            180 -> 1 - x to 1 - y
            270 -> 1 - y to x
            else -> x to y
        }
    }

    private fun turn(c: Corner, rotation: Int): Corner = when (((rotation % 360) + 360) % 360) {
        90 -> Corner(1f - c.y, c.x)
        180 -> Corner(1f - c.x, 1f - c.y)
        270 -> Corner(c.y, 1f - c.x)
        else -> c
    }

    /**
     * [pageToSource] for a whole recipe: also undoes flattening when [PageEdits.flatten] is on,
     * so a point on the page people see lands exactly where the renderer took it from.
     */
    fun pageToSource(edits: PageEdits, point: Corner): Corner {
        val curve = edits.curve?.takeIf { edits.flatten && it.lines.isNotEmpty() }
            ?: return pageToSource(edits.quad, edits.rotation, point)
        val (u, v) = unturn(point, edits.rotation)
        val bent = Bend.unflatten(curve, u.toFloat(), v.toFloat())
        return Homography.squareTo(edits.quad).map(u, bent.toDouble())
    }

    /** The inverse of [pageToSource] for a whole recipe. */
    fun sourceToPage(edits: PageEdits, point: Corner): Corner {
        val curve = edits.curve?.takeIf { edits.flatten && it.lines.isNotEmpty() }
            ?: return sourceToPage(edits.quad, edits.rotation, point)
        val p = Homography.squareTo(edits.quad).inverse().map(point.x.toDouble(), point.y.toDouble())
        return turn(Corner(p.x, Bend.flatten(curve, p.x, p.y)), edits.rotation)
    }

    /** Each blacked-out area of [edits] as a polygon on the page as shown. */
    fun redactionsOnPage(edits: PageEdits): List<List<Corner>> =
        edits.redactions.map { area -> area.map { sourceToPage(edits, it) } }

    /** Even-odd test; works for any simple polygon. */
    fun contains(polygon: List<Corner>, p: Corner): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun clampToUnit(quad: Quad): Quad = Quad.of(quad.corners.map { Corner(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) })

    /** Output size for a warp: averaged opposite edges, in the pixel space of a [width]×[height] image. */
    fun warpSize(quad: Quad, width: Int, height: Int): Pair<Int, Int> {
        fun len(a: Corner, b: Corner) = hypot((a.x - b.x) * width, (a.y - b.y) * height)
        val w = (len(quad.topLeft, quad.topRight) + len(quad.bottomLeft, quad.bottomRight)) / 2
        val h = (len(quad.topLeft, quad.bottomLeft) + len(quad.topRight, quad.bottomRight)) / 2
        return w.toInt().coerceAtLeast(1) to h.toInt().coerceAtLeast(1)
    }
}
