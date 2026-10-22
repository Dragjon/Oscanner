package dev.olaf.oscanner.core.imaging

import android.graphics.Bitmap
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.Quad
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A page outline found in one frame, normalized to that frame, plus how sharp the frame was.
 * [followed] means it was found only near where it was before, not afresh.
 */
data class Detection(val quad: Quad?, val sharpness: Double, val followed: Boolean = false)

/**
 * Finds the page in a grayscale frame (PLAN.md §5.4). The search runs on a small copy of the
 * frame, where text blurs into gray and a page's outline stays a clean step:
 *
 * 1. Candidates: four-corner fits to contours of two edge maps and an Otsu mask, plus quads
 *    made of pairs of long straight lines, which survive a thumb over a corner or clutter
 *    touching the page.
 * 2. Every candidate is checked against the image. Each side has to run along an edge of one
 *    polarity (paper lighter than the desk, or darker, the same on all four sides), still be
 *    there once text is filled in, and at least three sides have to be a sharp step: light
 *    falling off across a wall is a ramp, and a book's gutter is the one soft side allowed.
 * 3. An outline that is really two things side by side (two sheets, or both pages of a book
 *    where one runs out of view) loses to the single sheet inside it.
 * 4. The winner's sides are snapped onto the edge at full resolution.
 *
 * Not thread-safe: one per thread.
 */
class DocumentDetector(
    /** How far from square a corner may look, in degrees. Whiteboards are shot at steeper angles. */
    private val angleTolerance: Double = 35.0,
    /** Book mode wants an open book as one outline, which it then splits at the gutter. */
    private val keepSpreads: Boolean = false,
    /**
     * An ID card: 85.6 by 54 mm, so outlines near that shape win. Its face is printed (a colored
     * band, a photo), so a side may be lighter than what's behind it in one place and darker in
     * another, and the plain part under a band, sharing three of its sides, isn't a sheet of its own.
     */
    private val cards: Boolean = false,
    /** Asked where the page is when the search below finds nothing; see [PageFinder]. */
    private val finder: PageFinder? = null,
) {

    private val small = Mat()
    private val plain = Mat()
    private val edges = Mat()
    private val plainEdges = Mat()
    private val mask = Mat()
    private val lines = Mat()
    private val laplacian = Mat()
    private val closeText = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(CLOSE_TEXT, CLOSE_TEXT))
    private val closeGaps = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

    /** The small frame as it is, the small frame with text filled in, and the full frame. */
    private val raw = Field()
    private val filled = Field()
    private val full = Field(onDemand = true)

    /**
     * [gray] is a single-channel 8-bit frame, ideally around 640 px on its long side. [prior] is
     * where the page was in the frame before, if anywhere: when the search here finds nothing,
     * the page is looked for near there, on weaker evidence. Telling whether a page is still
     * where it was is easier than finding it, so a faint page stays found while the camera moves.
     */
    fun detect(gray: Mat, prior: Quad? = null, search: Boolean = true): Detection {
        val sharpness = sharpness(gray)
        // A page found in the frame before is looked for where it was first: snapping its sides
        // onto the edges near there takes a fraction of the time of a search, so the outline
        // keeps up with the hand. The search runs when that fails, and whenever no prior is given.
        full.load(gray)
        if (prior != null && search && sharpness >= FOLLOW_BLURRY) {
            follow(gray, prior)?.let { return Detection(it, sharpness, followed = true) }
        }
        val scale = SEARCH_SIDE.toDouble() / max(gray.cols(), gray.rows())
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        raw.load(small)
        val width = raw.width
        val height = raw.height

        Imgproc.Canny(raw.image, edges, LOW, HIGH)
        // Closing the image fills in text, strokes a pixel or two wide at this size, so lines and
        // outlines found on it aren't lost among letters. It also fills a faint book gutter,
        // which is why the outlines of the unfilled frame are kept as candidates too.
        Imgproc.morphologyEx(raw.image, plain, Imgproc.MORPH_CLOSE, closeText)
        filled.load(plain)
        Imgproc.Canny(plain, plainEdges, LOW, HIGH)

        val candidates = mutableListOf<List<P>>()
        Imgproc.morphologyEx(edges, mask, Imgproc.MORPH_CLOSE, closeGaps)
        candidates += contourCandidates(mask)
        Imgproc.threshold(raw.image, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        candidates += contourCandidates(mask)
        Imgproc.morphologyEx(plainEdges, mask, Imgproc.MORPH_CLOSE, closeGaps)
        candidates += contourCandidates(mask)
        candidates += lineCandidates(width, height)

        // Not searching: only the prior, snapped onto the page's edge (see [detect] for photos).
        // The three masks often trace the same outline; each is checked once.
        val valid = if (search) candidates.distinct().mapNotNull { check(it, width, height) } else emptyList()
        val found = valid
            .filter { outer ->
                keepSpreads || cards || valid.none { inner ->
                    inner.whole && inner.polarity == outer.polarity && sharesSide(inner.quad, outer.quad) &&
                        // Two sheets side by side; or a sheet with a strip of darker desk taken in
                        // beside it, out to the edge of a mat or the table, which the camera
                        // would show one frame and not the next.
                        (inner.area < UNION_SHARE * outer.area || (inner.area < BAND_SHARE * outer.area && darkerBand(inner.quad, outer.quad, width, height)))
                }
            }
            .maxByOrNull { it.score }
            ?.quad

        val sx = gray.cols().toDouble() / width
        val sy = gray.rows().toDouble() / height
        fun toFull(q: Quad) = q.corners.map { P(it.x * width * sx, it.y * height * sy) }
        fun toQuad(points: List<P>) = QuadGeometry.order(points.map { Corner((it.x / gray.cols()).toFloat(), (it.y / gray.rows()).toFloat()) })
        if (found != null) {
            val outline = inset(toQuad(refine(toFull(found))))

            // A book: the edge search often finds one page of the spread, its edges the clearest.
            // The finder sees the whole spread; when what it sees holds that page and is about
            // twice its size, it's the spread.
            if (keepSpreads && finder != null) {
                val spread = fromFinder(gray, width, height)?.let { rough ->
                    val reach = max(REFINE_REACH, (FINDER_REACH * max(gray.cols(), gray.rows())).toInt())
                    snapToPaper(toFull(rough), reach)?.let { inset(toQuad(it)) } ?: inset(rough)
                }
                if (spread != null && isSpreadOf(spread, outline)) return Detection(spread, sharpness)
            }
            return Detection(outline, sharpness)
        }
        if (prior != null) {
            // A blurred frame (the phone moving) can't place the page better than the last one did.
            if (sharpness < FOLLOW_BLURRY) return Detection(prior, sharpness, followed = true)
            // When searching, it was already followed above, and failed.
            if (!search) follow(gray, prior)?.let { return Detection(it, sharpness, followed = true) }
        }
        // The finder's outline is rough (its mask is coarse): its sides are snapped onto the
        // page's own edge, looked for further out, and it only counts if that edge is there.
        val rough = fromFinder(gray, width, height) ?: return Detection(null, sharpness)
        val reach = max(REFINE_REACH, (FINDER_REACH * max(gray.cols(), gray.rows())).toInt())
        // When its sides don't snap (pages bowing up toward a spine, or a busy cloth along them),
        // the finder's own outline stands: it already had to be sure all over and fill its shape.
        val snapped = snapToPaper(toFull(rough), reach) ?: return Detection(inset(rough), sharpness)
        return Detection(inset(toQuad(snapped)), sharpness)
    }

    /**
     * [prior] snapped onto the page's edges near where it was in [gray] (loaded as [full]), or
     * null if they aren't there. The prior was inset when it was found; its sides are snapped
     * from where the edge was.
     */
    private fun follow(gray: Mat, prior: Quad): Quad? {
        val reach = max(REFINE_REACH, (FOLLOW_REACH * max(gray.cols(), gray.rows())).toInt())
        val corners = outset(prior).corners.map { P(it.x.toDouble() * gray.cols(), it.y.toDouble() * gray.rows()) }
        val followed = snapToPaper(corners, reach, maxMove = 1.5 * reach, nearest = true)
            ?.let(::refine)
            ?.let { points -> QuadGeometry.order(points.map { Corner((it.x / gray.cols()).toFloat(), (it.y / gray.rows()).toFloat()) }) }
        return followed?.takeIf(::plausible)?.let(::inset)
    }

    /**
     * Finds the page in a still photo; null when nothing page-shaped is there. [seen] is where the
     * camera showed the page as the photo was taken: that outline held still on the screen, so
     * it's trusted over what the photo alone suggests. The photo's own outline is kept only when
     * it agrees; otherwise the camera's is snapped onto the page's edge in the photo, or kept as
     * it was.
     */
    fun detect(bitmap: Bitmap, seen: Quad? = null): Quad? {
        val scale = DETECT_SIDE.toDouble() / max(bitmap.width, bitmap.height)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        if (scale < 1.0) Imgproc.resize(gray, gray, Size(), scale, scale, Imgproc.INTER_AREA)
        try {
            return inPhoto(gray, seen) { colorOf(rgba(bitmap)) }
        } finally {
            gray.release()
        }
    }

    /** [detect] for a photo already in gray, [color] giving its [colorOf] for cards. */
    internal fun inPhoto(gray: Mat, seen: Quad?, color: () -> Mat? = { null }): Quad? {
        val inGray = detect(gray).quad
        val found = if (cards) wholeCard(inGray, color()?.let { c -> detect(c).quad.also { c.release() } }) else inGray
        if (seen == null) return found
        if (found != null && QuadGeometry.iou(found, seen) >= AGREES_WITH_CAMERA) return found
        detect(gray, prior = seen, search = false).quad?.let { return it }
        // The page moved between the camera's last look and the photo, too far to snap onto: the
        // photo's own outline of it is where it is now. The camera's stale one would take in desk.
        if (found != null && QuadGeometry.iou(found, seen) >= SAME_PAGE) return found
        return seen
    }

    /** The photo's colors, for [colorOf]. */
    private fun rgba(bitmap: Bitmap): Mat = Mat().also { Utils.bitmapToMat(bitmap, it) }

    /**
     * How colorful each pixel of [rgba] is, at the size of the gray frame searched before, which
     * this replaces: a pastel card on a gray desk is plain in gray and stands out here.
     */
    private fun colorOf(rgba: Mat): Mat {
        val yuv = Mat()
        Imgproc.cvtColor(rgba, yuv, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        Imgproc.cvtColor(yuv, yuv, Imgproc.COLOR_RGB2YUV)
        val planes = mutableListOf<Mat>()
        Core.split(yuv, planes)
        yuv.release()
        val out = Mat()
        colorfulness(planes[1], planes[2], out)
        planes.forEach(Mat::release)
        val scale = DETECT_SIDE.toDouble() / max(out.cols(), out.rows())
        if (scale < 1.0) Imgproc.resize(out, out, Size(), scale, scale, Imgproc.INTER_AREA)
        return out
    }

    fun release() {
        listOf(small, plain, edges, plainEdges, mask, lines, laplacian, closeText, closeGaps).forEach(Mat::release)
        listOf(raw, filled, full).forEach(Field::release)
    }

    /**
     * The page as [finder] sees it: the biggest confident blob of its mask, as four corners in
     * small-frame terms, if it's page-shaped. Its sides needn't show as edges (that's why it was
     * asked), but it must be a sensible quadrilateral, not touching two frame edges.
     */
    private fun fromFinder(gray: Mat, width: Int, height: Int): Quad? {
        val finder = finder ?: return null
        val sure = finder.pageMask(gray) ?: return null
        // Half a cell of the mask: its outline sits about that far inside the page's true edge.
        val cell = 0.5 * max(width.toDouble() / sure.cols(), height.toDouble() / sure.rows())
        // A page running off the frame can't be outlined: the mask would reach the frame's edge.
        // And a page is something the finder is sure of all over, not a blur it half believes.
        if (touchesEdge(sure) || !confident(sure)) {
            sure.release()
            return null
        }
        val big = Mat()
        Imgproc.resize(sure, big, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        sure.release()
        Imgproc.threshold(big, big, FINDER_SURE, 255.0, Imgproc.THRESH_BINARY)
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(big, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        listOf(big, hierarchy).forEach(Mat::release)
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
        val blob = largest?.let { Imgproc.contourArea(it) } ?: 0.0
        val points = largest?.takeIf { blob >= width * height * MIN_AREA_RATIO }?.let { fitFour(hull(it)) }
        contours.forEach(Mat::release)
        points ?: return null
        // A ragged blob (a bright patch, a lamp's glow) fills its four-corner fit poorly; a page fills it.
        if (blob < FINDER_SOLID * polygonArea(points)) return null
        val cx = points.sumOf { it.x } / 4
        val cy = points.sumOf { it.y } / 4
        val grown = points.map { p ->
            val d = hypot(p.x - cx, p.y - cy).coerceAtLeast(1.0)
            P(p.x + (p.x - cx) / d * cell * SQRT2, p.y + (p.y - cy) / d * cell * SQRT2)
        }
        val quad = QuadGeometry.order(grown.map { Corner((it.x / width).toFloat(), (it.y / height).toFloat()) })
        if (!QuadGeometry.isConvex(quad)) return null
        val area = QuadGeometry.area(quad.corners)
        if (area < MIN_AREA_RATIO || area > 0.98) return null
        if (quad.corners.count { it.x < BORDER || it.x > 1 - BORDER || it.y < BORDER || it.y > 1 - BORDER } >= 2) return null
        if (QuadGeometry.angles(quad).any { abs(it - 90.0) > angleTolerance }) return null
        return quad
    }

    /** Whether [spread] is two pages, one of them [page]: about twice its area, holding nearly all of it. */
    private fun isSpreadOf(spread: Quad, page: Quad): Boolean {
        val a = QuadGeometry.area(page.corners)
        val b = QuadGeometry.area(spread.corners)
        if (b < SPREAD_MIN * a || b > SPREAD_MAX * a) return false
        val iou = QuadGeometry.iou(page, spread)
        val shared = iou * (a + b) / (1 + iou)
        return shared >= SPREAD_HOLDS * a
    }

    /** Whether what [outer] takes in beyond [inner] is clearly darker than [inner]: desk, not paper. */
    private fun darkerBand(inner: Quad, outer: Quad, width: Int, height: Int): Boolean {
        fun shape(q: Quad) = MatOfPoint(*q.corners.map { Point(it.x * width.toDouble(), it.y * height.toDouble()) }.toTypedArray())
        val inMask = Mat.zeros(height, width, CvType.CV_8UC1)
        val outMask = Mat.zeros(height, width, CvType.CV_8UC1)
        val a = shape(inner)
        val b = shape(outer)
        Imgproc.fillConvexPoly(inMask, a, Scalar(255.0))
        Imgproc.fillConvexPoly(outMask, b, Scalar(255.0))
        // A pixel or two of the band next to the page is the page's own soft edge: left out.
        val grown = Mat()
        Imgproc.dilate(inMask, grown, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
        Core.subtract(outMask, grown, outMask)
        val band = Core.countNonZero(outMask)
        val result = band > 0 && Core.mean(raw.image, outMask).`val`[0] < BAND_DARKER * Core.mean(raw.image, inMask).`val`[0]
        listOf(inMask, outMask, grown, a, b).forEach(Mat::release)
        return result
    }

    private fun pixels(mask: Mat) = ByteArray(mask.rows() * mask.cols()).also { mask.get(0, 0, it) }

    private fun confident(mask: Mat): Boolean {
        val values = pixels(mask).map { it.toInt() and 0xFF }.filter { it > FINDER_SURE }
        return values.isNotEmpty() && values.count { it > FINDER_CERTAIN } >= FINDER_CONFIDENT * values.size
    }

    /**
     * Whether the page the finder sees runs off the frame: its biggest sure blob reaching the
     * frame's rim. Another blob at the rim (the next page of a booklet peeking in) doesn't count.
     */
    private fun touchesEdge(mask: Mat): Boolean {
        val rows = mask.rows()
        val cols = mask.cols()
        val sureMask = Mat()
        Imgproc.threshold(mask, sureMask, FINDER_SURE, 255.0, Imgproc.THRESH_BINARY)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(sureMask, labels, stats, centroids, 4, CvType.CV_32S)
        val biggest = (1 until n).maxByOrNull { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
        val label = IntArray(rows * cols).also { labels.get(0, 0, it) }
        listOf(sureMask, labels, stats, centroids).forEach(Mat::release)
        biggest ?: return false
        fun sure(x: Int, y: Int) = label[y * cols + x] == biggest
        val rim = (0 until cols).count { sure(it, 0) || sure(it, rows - 1) } +
            (0 until rows).count { sure(0, it) || sure(cols - 1, it) }
        return rim > FINDER_EDGE * (rows + cols)
    }

    private fun polygonArea(points: List<P>): Double =
        abs(points.indices.sumOf { i -> points[i].x * points[(i + 1) % points.size].y - points[(i + 1) % points.size].x * points[i].y }) / 2

    /**
     * Whether the full frame steps down across (x, y) going against (nx, ny): the middle of a
     * few pixels just outside is darker than the middle of a few just inside.
     */
    private fun isStepAt(x: Double, y: Double, nx: Double, ny: Double): Boolean =
        middle(x, y, nx, ny, STEP_NEAR) - middle(x, y, nx, ny, -STEP_FAR) >= STEP_MIN_LEVELS

    private val window = IntArray(STEP_FAR - STEP_NEAR + 1)

    /** The upper middle of the pixels from [from] on along (nx, ny), as many as STEP_NEAR..STEP_FAR. */
    private fun middle(x: Double, y: Double, nx: Double, ny: Double, from: Int): Int {
        for (k in window.indices) {
            val v = full.at(x + nx * (from + k), y + ny * (from + k))
            var j = k
            while (j > 0 && window[j - 1] > v) {
                window[j] = window[j - 1]
                j--
            }
            window[j] = v
        }
        return window[window.size / 2]
    }

    /** A page-shaped outline: convex, a sensible size, square enough, not running out of view. */
    private fun plausible(quad: Quad): Boolean {
        if (!QuadGeometry.isConvex(quad)) return false
        val area = QuadGeometry.area(quad.corners)
        if (area < MIN_AREA_RATIO || area > 0.98) return false
        if (quad.corners.count { it.x < BORDER || it.x > 1 - BORDER || it.y < BORDER || it.y > 1 - BORDER } >= 2) return false
        return QuadGeometry.angles(quad).none { abs(it - 90.0) > angleTolerance }
    }

    /** Variance of the Laplacian: low when the frame is blurred by motion or focus. */
    private fun sharpness(gray: Mat): Double {
        // Whole numbers well inside 16 bits: the same variance as in floating point, sooner.
        Imgproc.Laplacian(gray, laplacian, CvType.CV_16S)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(laplacian, mean, std)
        val sd = std.toArray().firstOrNull() ?: 0.0
        mean.release()
        std.release()
        return sd * sd
    }

    /**
     * Four-corner fits to the hulls of the largest contours of [mask]. Inner contours count too:
     * the inside of an edge loop around one page of a book is that page alone.
     */
    private fun contourCandidates(mask: Mat): List<List<P>> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = mask.total() * MIN_AREA_RATIO * 0.7
        val result = contours
            .map { it to Imgproc.contourArea(it) }
            .filter { it.second >= minArea }
            .sortedByDescending { it.second }
            .take(MAX_CONTOURS)
            .mapNotNull { fitFour(hull(it.first)) }
        contours.forEach(Mat::release)
        return result
    }

    private fun hull(contour: MatOfPoint): MatOfPoint2f {
        val indices = MatOfInt()
        Imgproc.convexHull(contour, indices)
        val points = contour.toArray()
        val hull = MatOfPoint2f(*indices.toArray().map { points[it] }.toTypedArray())
        indices.release()
        return hull
    }

    /** Douglas–Peucker with a growing tolerance until four vertices remain. */
    private fun fitFour(hull: MatOfPoint2f): List<P>? {
        val perimeter = Imgproc.arcLength(hull, true)
        val approx = MatOfPoint2f()
        try {
            for (step in 1..8) {
                Imgproc.approxPolyDP(hull, approx, perimeter * 0.01 * step, true)
                val n = approx.total().toInt()
                if (n == 4) return approx.toArray().map { P(it.x, it.y) }
                if (n < 4) return null
            }
            return null
        } finally {
            approx.release()
            hull.release()
        }
    }

    /**
     * Quads from pairs of long straight edges, one pair running across and one down. Segments
     * whose edge flips between lighter and darker along them are dropped first: those are
     * strung through text or wood grain, not along a sheet.
     */
    private fun lineCandidates(width: Int, height: Int): List<List<P>> {
        Imgproc.HoughLinesP(plainEdges, lines, 1.0, PI / 180, 20, min(width, height) * 0.15, 5.0)
        val merged = mutableListOf<Line>()
        for (i in 0 until lines.rows()) {
            val s = lines.get(i, 0)
            val a = P(s[0], s[1])
            val b = P(s[2], s[3])
            if (raw.support(a, b, P(b.y - a.y, a.x - b.x)).share < 0.7) continue
            var theta = atan2(b.y - a.y, b.x - a.x) % PI
            if (theta < 0) theta += PI
            val rho = -a.x * sin(theta) + a.y * cos(theta)
            val length = hypot(b.x - a.x, b.y - a.y)
            val same = merged.firstOrNull { l ->
                val dt = abs(l.theta - theta).let { min(it, PI - it) }
                dt < MERGE_ANGLE && abs(l.distance(a)) < 3 && abs(l.distance(b)) < 3
            }
            if (same != null) same.length += length else merged += Line(theta, rho, length)
        }
        merged.sortByDescending { it.length }
        val across = merged.filter { it.theta < PI / 4 || it.theta > 3 * PI / 4 }.take(MAX_LINES)
        val down = merged.filter { it.theta >= PI / 4 && it.theta <= 3 * PI / 4 }.take(MAX_LINES)

        val result = mutableListOf<List<P>>()
        for (i in across.indices) for (j in i + 1 until across.size) {
            for (k in down.indices) for (m in k + 1 until down.size) {
                val corners = listOf(
                    across[i].meet(down[k]), across[i].meet(down[m]),
                    across[j].meet(down[k]), across[j].meet(down[m]),
                )
                if (corners.any { it == null || it.x < -2 || it.x > width + 2 || it.y < -2 || it.y > height + 2 }) continue
                result += corners.filterNotNull()
            }
        }
        return result
    }

    /** Scores [points] (small-frame pixels) as a page, or null when it can't be one. */
    private fun check(points: List<P>, width: Int, height: Int): Candidate? {
        val quad = QuadGeometry.order(points.map { Corner((it.x / width).toFloat(), (it.y / height).toFloat()) })
        if (!QuadGeometry.isConvex(quad)) return null
        val area = QuadGeometry.area(quad.corners)
        if (area < MIN_AREA_RATIO || area > 0.98) return null
        // Two corners on the frame edge means the shape runs out of view: a wall, a table, or
        // a page that isn't fully in the picture. None of those can be scanned whole.
        val onBorder = quad.corners.count { it.x < BORDER || it.x > 1 - BORDER || it.y < BORDER || it.y > 1 - BORDER }
        if (onBorder >= 2) return null
        val angles = QuadGeometry.angles(quad)
        if (angles.any { abs(it - 90.0) > angleTolerance }) return null

        val corners = quad.corners.map { P(it.x.toDouble() * width, it.y.toDouble() * height) }
        // A sliver isn't a page: the margin strip beside a booklet's text, a ruler. Paper is
        // about 1.4 long to wide; a long receipt reaches 4.
        val across = (hypot(corners[1].x - corners[0].x, corners[1].y - corners[0].y) + hypot(corners[2].x - corners[3].x, corners[2].y - corners[3].y)) / 2
        val down = (hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y) + hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)) / 2
        if (max(across, down) > MAX_SIDE_RATIO * min(across, down)) return null
        val shape = if (cards) cardShape(max(across, down) / min(across, down)) ?: return null else 1.0
        val sides = sides(corners)
        // Most candidates fail on a side or two, so each side is measured only if the ones before passed.
        val support = ArrayList<Support>(4)
        for ((a, b, inward) in sides) {
            val s = raw.support(a, b, inward, either = cards)
            if (s.share < SIDE_MIN || (!cards && support.isNotEmpty() && s.polarity != support[0].polarity)) return null
            support += s
        }
        val shares = support.map { it.share }
        if (shares.average() < MEAN_MIN) return null
        // With text filled in, a row of text has no edge left while paper still does. But on a
        // light counter full of dark flecks (terrazzo, granite), filling them in makes the
        // counter as white as the paper: a side that is a strong, clean step as it is, with the
        // paper the lighter side, counts.
        // Worked out only for the sides that need it: it's the slowest test here.
        val sureCache = arrayOfNulls<Boolean>(4)
        fun sure(i: Int): Boolean = sureCache[i] ?: run {
            val (a, b, inward) = sides[i]
            (shares[i] >= SURE_SIDE && support[i].polarity > 0 && raw.isStep(a, b, inward) && flecked(a, b, inward)).also { sureCache[i] = it }
        }
        val filledShares = DoubleArray(4)
        for (i in 0 until 4) {
            val (a, b, inward) = sides[i]
            filledShares[i] = filled.support(a, b, inward, either = cards).share
            if (filledShares[i] < SIDE_MIN && !sure(i)) return null
        }
        val filledMin = filledShares.min()
        val steps = sides.indices.count { i -> val (a, b, inward) = sides[i]; filled.isStep(a, b, inward) || sure(i) }
        // One side may be a soft fold, like a book's gutter.
        if (steps < 3) return null

        val skew = angles.sumOf { abs(it - 90.0) } / 4 / angleTolerance
        val mean = shares.average()
        val score = mean * mean * shares.min() * shares.min() * area * (1.0 - 0.5 * skew) * shape *
            if (support[0].polarity > 0) 1.0 else DARK_PAGE
        val whole = steps == 4 && min(shares.min(), filledMin) >= WHOLE
        return Candidate(quad, area, support[0].polarity, score, whole)
    }

    /**
     * How card-shaped an outline [ratio] long to wide is, 1 for a card's own 1.586 and less the
     * further off; null when it can't be a card at all, even seen at an angle.
     */
    private fun cardShape(ratio: Double): Double? {
        if (ratio < CARD_RATIO_MIN || ratio > CARD_RATIO_MAX) return null
        val off = ln(ratio / CARD_RATIO) / CARD_SPREAD
        return exp(-off * off)
    }

    /**
     * Whether what lies just outside side a→b is flecked with dark bits that filling in text
     * also fills in: the counter brightens noticeably once they're gone.
     */
    private fun flecked(a: P, b: P, inward: P): Boolean {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        var nx = -dy / length
        var ny = dx / length
        if (nx * inward.x + ny * inward.y > 0) {
            nx = -nx
            ny = -ny
        }
        val n = max(8, (length / 2).toInt())
        var gain = 0.0
        var count = 0
        for (s in 0 until n) {
            val t = 0.1 + 0.8 * s / (n - 1)
            for (o in 3..STEP_REACH) {
                val x = a.x + dx * t + nx * o
                val y = a.y + dy * t + ny * o
                gain += filled.at(x, y) - raw.at(x, y)
                count++
            }
        }
        return count > 0 && gain / count >= FLECK_GAIN
    }

    /** Each side as (start, end, a vector pointing into the quad). */
    private fun sides(corners: List<P>): List<Triple<P, P, P>> {
        val cx = corners.sumOf { it.x } / 4
        val cy = corners.sumOf { it.y } / 4
        return List(4) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            Triple(a, b, P(cx - (a.x + b.x) / 2, cy - (a.y + b.y) / 2))
        }
    }

    /**
     * Snaps each side onto the strongest edge within a few pixels of it, at full resolution, and
     * re-intersects the corners. Gives up (keeping [corners]) if that moves anything far.
     */
    private fun refine(corners: List<P>): List<P> {
        val fitted = List(4) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            val nx = -dy / length
            val ny = dx / length
            val n = max(8, (length / 4).toInt())
            val xs = ArrayList<Double>(n)
            val ys = ArrayList<Double>(n)
            for (s in 0 until n) {
                val t = 0.08 + 0.84 * s / (n - 1)
                val x0 = a.x + dx * t
                val y0 = a.y + dy * t
                var best = 0.0
                var bestOffset = Int.MIN_VALUE
                for (o in -REFINE_REACH..REFINE_REACH) {
                    val g = abs(full.gradient(x0 + nx * o, y0 + ny * o, nx, ny))
                    if (g > best) {
                        best = g
                        bestOffset = o
                    }
                }
                if (bestOffset != Int.MIN_VALUE && best >= GRAD_MIN) {
                    xs += x0 + nx * bestOffset
                    ys += y0 + ny * bestOffset
                }
            }
            if (xs.size < 6) Line.through(a, b) else Line.fit(xs, ys)
        }
        val out = List(4) { i -> fitted[(i + 3) % 4].meet(fitted[i]) ?: return corners }
        if (out.indices.any { hypot(out[it].x - corners[it].x, out[it].y - corners[it].y) > MAX_SNAP }) return corners
        return out
    }

    /**
     * Each side moved onto the outermost strong edge nearby with paper on the inside (lighter
     * inward), within [reach] pixels either way, or with [nearest] the one closest to where it is; null unless most of every side finds one, or if
     * a corner then lands far from where it was.
     */
    private fun snapToPaper(corners: List<P>, reach: Int, maxMove: Double = 3.0 * reach, nearest: Boolean = false): List<P>? {
        val fitted = List(4) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            // Corners go round clockwise, so this normal points into the page.
            val nx = -dy / length
            val ny = dx / length
            val n = max(8, (length / 4).toInt())
            val xs = ArrayList<Double>(n)
            val ys = ArrayList<Double>(n)
            val g = DoubleArray(2 * reach + 1)
            for (s in 0 until n) {
                val t = 0.08 + 0.84 * s / (n - 1)
                val x0 = a.x + dx * t
                val y0 = a.y + dy * t
                for (k in g.indices) g[k] = full.gradient(x0 + nx * (k - reach), y0 + ny * (k - reach), nx, ny)
                val best = g.max()
                if (best >= FINDER_GRAD) {
                    // The page's own edge is the outermost one: lines printed on it are further in.
                    // And it's a step, darker all the way out, where a ruled line is paper again
                    // just past it.
                    fun isEdge(k: Int) = g[k] >= FINDER_OUTER * best && isStepAt(x0 + nx * (k - reach), y0 + ny * (k - reach), nx, ny)
                    // Following a page already found, the edge nearest where it was is the page's:
                    // a shadow or a desk's edge just outside is a step too. Looked for outward from
                    // there, inner side first at each distance, as the nearest was picked before.
                    var i: Int? = null
                    if (nearest) {
                        for (d in 0..reach) {
                            if (isEdge(reach - d)) { i = reach - d; break }
                            if (d > 0 && isEdge(reach + d)) { i = reach + d; break }
                        }
                    } else {
                        i = g.indices.firstOrNull(::isEdge)
                    }
                    if (i != null) {
                        xs += x0 + nx * (i - reach)
                        ys += y0 + ny * (i - reach)
                    }
                }
            }
            if (xs.size < n * FINDER_SIDE) return null
            // Refit without the points a stray edge in the texture pulled away from the line.
            val first = Line.fit(xs, ys)
            val off = xs.indices.map { abs(first.distance(P(xs[it], ys[it]))) }
            val limit = max(TRIM_MIN, TRIM * off.sorted()[off.size / 2])
            val keep = xs.indices.filter { off[it] <= limit }
            if (keep.size < xs.size / 2) first else Line.fit(keep.map { xs[it] }, keep.map { ys[it] })
        }
        val snapped = List(4) { i -> fitted[(i + 3) % 4].meet(fitted[i]) ?: return null }
        return snapped.takeIf { snapped.indices.all { i -> hypot(snapped[i].x - corners[i].x, snapped[i].y - corners[i].y) <= maxMove } }
    }

    /**
     * Pulls each corner a hair toward the center. Contours sit on the boundary pixels, so the
     * raw outline keeps a sliver of desk along the edges once warped.
     */
    private fun inset(quad: Quad): Quad {
        val cx = quad.corners.map { it.x }.average().toFloat()
        val cy = quad.corners.map { it.y }.average().toFloat()
        return Quad.of(quad.corners.map { Corner(it.x + (cx - it.x) * INSET, it.y + (cy - it.y) * INSET) })
    }

    /** Undoes [inset]. */
    private fun outset(quad: Quad): Quad {
        val cx = quad.corners.map { it.x }.average().toFloat()
        val cy = quad.corners.map { it.y }.average().toFloat()
        val k = 1f / (1f - INSET)
        return Quad.of(quad.corners.map { Corner(cx + (it.x - cx) * k, cy + (it.y - cy) * k) })
    }

    private class Candidate(val quad: Quad, val area: Double, val polarity: Int, val score: Double, val whole: Boolean)

    private data class P(val x: Double, val y: Double)

    private class Support(val share: Double, val polarity: Int)

    /** A line as angle and distance from the origin, with how much edge was found along it. */
    private class Line(val theta: Double, val rho: Double, var length: Double = 0.0) {
        private val nx = -sin(theta)
        private val ny = cos(theta)

        fun distance(p: P) = p.x * nx + p.y * ny - rho

        fun meet(other: Line): P? {
            val det = nx * other.ny - ny * other.nx
            if (abs(det) < 1e-9) return null
            return P((rho * other.ny - ny * other.rho) / det, (nx * other.rho - rho * other.nx) / det)
        }

        companion object {
            fun through(a: P, b: P): Line {
                val theta = atan2(b.y - a.y, b.x - a.x)
                return Line(theta, -a.x * sin(theta) + a.y * cos(theta))
            }

            /** Total least squares: the line through the mean along the points' main direction. */
            fun fit(xs: List<Double>, ys: List<Double>): Line {
                val mx = xs.average()
                val my = ys.average()
                var sxx = 0.0
                var syy = 0.0
                var sxy = 0.0
                for (i in xs.indices) {
                    val dx = xs[i] - mx
                    val dy = ys[i] - my
                    sxx += dx * dx
                    syy += dy * dy
                    sxy += dx * dy
                }
                val theta = 0.5 * atan2(2 * sxy, sxx - syy)
                return Line(theta, -mx * sin(theta) + my * cos(theta))
            }
        }
    }

    /** A grayscale image, lightly blurred, and its gradients, copied out for per-pixel reads. */
    /**
     * A frame, softened, with its gradients. [onDemand]: worked out at each point asked for, the
     * same sums Sobel makes, for a frame where only a few hundred points along an outline are read.
     */
    private class Field(private val onDemand: Boolean = false) {
        val image = Mat()
        private val sobel = Mat()
        var width = 0
            private set
        var height = 0
            private set
        private var pixels = ByteArray(0)
        private var gx = ShortArray(0)
        private var gy = ShortArray(0)

        fun load(gray: Mat) {
            Imgproc.GaussianBlur(gray, image, Size(3.0, 3.0), 0.0)
            width = image.cols()
            height = image.rows()
            val n = width * height
            if (pixels.size != n) pixels = ByteArray(n)
            image.get(0, 0, pixels)
            if (onDemand) return
            if (gx.size != n) {
                gx = ShortArray(n)
                gy = ShortArray(n)
            }
            Imgproc.Sobel(image, sobel, CvType.CV_16S, 1, 0, 3)
            sobel.get(0, 0, gx)
            Imgproc.Sobel(image, sobel, CvType.CV_16S, 0, 1, 3)
            sobel.get(0, 0, gy)
        }

        /** Intensity at the nearest pixel, clamped to the image. */
        fun at(x: Double, y: Double): Int {
            val px = x.roundToInt().coerceIn(0, width - 1)
            val py = y.roundToInt().coerceIn(0, height - 1)
            return pixels[py * width + px].toInt() and 0xFF
        }

        /** Gradient along (nx, ny) at the nearest pixel; 0 outside the image. */
        fun gradient(x: Double, y: Double, nx: Double, ny: Double): Double {
            val px = x.roundToInt()
            val py = y.roundToInt()
            if (px < 0 || py < 0 || px >= width || py >= height) return 0.0
            if (onDemand) {
                if (px < 1 || py < 1 || px >= width - 1 || py >= height - 1) return sobelX(px, py) * nx + sobelY(px, py) * ny
                // Inside the image, the same sums read straight off the rows above and below.
                val w = width
                val c = py * w + px
                val a = pixels
                fun v(i: Int) = a[i].toInt() and 0xFF
                val tl = v(c - w - 1); val tc = v(c - w); val tr = v(c - w + 1)
                val ml = v(c - 1); val mr = v(c + 1)
                val bl = v(c + w - 1); val bc = v(c + w); val br = v(c + w + 1)
                val sx = tr - tl + 2 * (mr - ml) + br - bl
                val sy = bl - tl + 2 * (bc - tc) + br - tr
                return sx * nx + sy * ny
            }
            val i = py * width + px
            return gx[i] * nx + gy[i] * ny
        }

        /** The pixel at (x, y), mirrored at the image's edge as OpenCV's filters do (reflect 101). */
        private fun px(x: Int, y: Int): Int {
            val cx = if (x < 0) -x else if (x >= width) 2 * width - 2 - x else x
            val cy = if (y < 0) -y else if (y >= height) 2 * height - 2 - y else y
            return pixels[cy * width + cx].toInt() and 0xFF
        }

        private fun sobelX(x: Int, y: Int): Int =
            px(x + 1, y - 1) - px(x - 1, y - 1) + 2 * (px(x + 1, y) - px(x - 1, y)) + px(x + 1, y + 1) - px(x - 1, y + 1)

        private fun sobelY(x: Int, y: Int): Int =
            px(x - 1, y + 1) - px(x - 1, y - 1) + 2 * (px(x, y + 1) - px(x, y - 1)) + px(x + 1, y + 1) - px(x + 1, y - 1)

        /**
         * How much of the segment a→b runs along an edge, counting only the more common polarity
         * (lighter toward [inward], or darker), or with [either] both, and which is more common.
         */
        fun support(a: P, b: P, inward: P, either: Boolean = false): Support {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length < 6) return Support(0.0, 0)
            var nx = -dy / length
            var ny = dx / length
            if (nx * inward.x + ny * inward.y < 0) {
                nx = -nx
                ny = -ny
            }
            val n = max(8, (length / 1.5).toInt())
            var lighter = 0
            var darker = 0
            for (s in 0 until n) {
                val t = 0.04 + 0.92 * s / (n - 1)
                val x0 = a.x + dx * t
                val y0 = a.y + dy * t
                var best = 0.0
                for (o in -2..3) {
                    val g = gradient(x0 + nx * o, y0 + ny * o, nx, ny)
                    if (abs(g) > abs(best)) best = g
                }
                if (best >= GRAD_MIN) lighter++ else if (best <= -GRAD_MIN) darker++
            }
            val along = if (either) lighter + darker else max(lighter, darker)
            return Support(along.toDouble() / n, if (lighter >= darker) 1 else -1)
        }

        /**
         * Averages the cross-section of side a→b along its length: paper ends in a step, while
         * light falling off over a wall or carpet makes a ramp.
         */
        fun isStep(a: P, b: P, inward: P): Boolean {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            var nx = -dy / length
            var ny = dx / length
            if (nx * inward.x + ny * inward.y < 0) {
                nx = -nx
                ny = -ny
            }
            val n = max(8, (length / 2).toInt())
            val profile = DoubleArray(2 * STEP_REACH + 1)
            for (s in 0 until n) {
                val t = 0.1 + 0.8 * s / (n - 1)
                val x0 = a.x + dx * t
                val y0 = a.y + dy * t
                for (j in profile.indices) {
                    val o = j - STEP_REACH
                    profile[j] += at(x0 + nx * o, y0 + ny * o).toDouble()
                }
            }
            for (j in profile.indices) profile[j] /= n
            val contrast = abs(profile.last() - profile.first())
            var steepest = 0.0
            for (j in 0 until profile.size - 2) steepest = max(steepest, abs(profile[j + 2] - profile[j]))
            // White paper on a light counter can sit only ten or so levels above it; its edge is
            // still a step if it changes almost all at once. Only paper lighter than what it's
            // on: a faint darker shape is far more often a door or a cupboard than a page.
            val lighterInside = profile.last() > profile.first()
            return (contrast >= MIN_CONTRAST && steepest >= STEEP * contrast) ||
                (lighterInside && contrast >= FAINT_CONTRAST && steepest >= FAINT_STEEP * contrast)
        }

        fun release() {
            image.release()
            sobel.release()
        }
    }

    companion object {
        /**
         * How far from gray each pixel is, from its [u] and [v] (128 is gray), into [out]:
         * scaled up so a pale tint reads as clearly as a step in brightness would.
         */
        fun colorfulness(u: Mat, v: Mat, out: Mat) {
            val a = Mat()
            val b = Mat()
            Core.absdiff(u, Scalar(128.0), a)
            Core.absdiff(v, Scalar(128.0), b)
            Core.addWeighted(a, COLOR_GAIN, b, COLOR_GAIN, 0.0, out)
            a.release()
            b.release()
        }

        /**
         * The card, from its outline in gray and in [colorfulness]: whichever was found, and when
         * both were, the one in color if it's clearly the bigger and holds the other. In gray a
         * card's band or photo can stand out more than its edge does.
         */
        fun wholeCard(inGray: Quad?, inColor: Quad?): Quad? {
            if (inGray == null || inColor == null) return inGray ?: inColor
            val a = QuadGeometry.area(inGray.corners)
            val b = QuadGeometry.area(inColor.corners)
            val iou = QuadGeometry.iou(inGray, inColor)
            val shared = iou * (a + b) / (1 + iou)
            return if (b >= CARD_WIDER * a && shared >= CARD_HOLDS * a) inColor else inGray
        }

        private const val CARD_WIDER = 1.1
        private const val CARD_HOLDS = 0.9

        /** Each step of U or V away from gray counts as this many of brightness. */
        private const val COLOR_GAIN = 3.0
        /** Pages smaller than this share of the frame are ignored. */
        const val MIN_AREA_RATIO = 0.08
        private const val MAX_SIDE_RATIO = 5.0
        /** An ID-1 card, long side to short; how far off it may look at an angle, and how fast that costs. */
        private const val CARD_RATIO = 1.586
        private const val CARD_RATIO_MIN = 1.2
        private const val CARD_RATIO_MAX = 2.1
        private const val CARD_SPREAD = 0.12
        /** A spread is this many times one page's area, and holds this share of it. */
        private const val SPREAD_MIN = 1.6
        private const val SPREAD_MAX = 2.6
        private const val SPREAD_HOLDS = 0.85
        /** A photo's own outline overlapping the camera's this much is the same page, better placed. */
        private const val AGREES_WITH_CAMERA = 0.9
        /** Overlapping the camera's outline this much, the photo's is the same page, moved. */
        private const val SAME_PAGE = 0.6
        /** How sure the [PageFinder] must be (0..255) that a spot is page. */
        private const val FINDER_SURE = 127.0
        private const val SQRT2 = 1.4142135623730951
        /** How far, as a share of the frame, a finder outline's sides are looked around. */
        private const val FINDER_REACH = 0.03
        /** A paper edge as faint as a few gray levels counts, along this share of each side. */
        private const val FINDER_GRAD = 8.0
        private const val FINDER_SIDE = 0.6
        /** How far, as a share of the frame, the page may have moved since the frame before. */
        private const val FOLLOW_REACH = 0.025
        /** Sharpness below which a frame is too blurred to follow the page in. */
        private const val FOLLOW_BLURRY = 150.0
        /** A step at a page edge: pixels this far either side differ by this much at the middle. */
        private const val STEP_NEAR = 2
        private const val STEP_FAR = 7
        private const val STEP_MIN_LEVELS = 5
        /** An edge this near the strongest counts, so the outermost of a page's edges is taken. */
        private const val FINDER_OUTER = 0.5
        /** Points further from a first fit than this many times the median (or pixels) are dropped. */
        private const val TRIM = 2.5
        private const val TRIM_MIN = 1.5
        /** The share of a finder outline its mask must fill, and of the frame's rim it may touch. */
        private const val FINDER_SOLID = 0.88
        private const val FINDER_EDGE = 0.02
        /** Most of a page's mask is near certain (above 230 of 255); a light patch's isn't. */
        private const val FINDER_CERTAIN = 230
        private const val FINDER_CONFIDENT = 0.75
        private const val DETECT_SIDE = 640
        /** The long side of the frame the search runs on. Text at this size is a gray haze. */
        private const val SEARCH_SIDE = 256.0
        private const val LOW = 15.0
        private const val HIGH = 40.0
        private const val CLOSE_TEXT = 5.0
        private const val MAX_CONTOURS = 30
        private const val MAX_LINES = 8
        private val MERGE_ANGLE = Math.toRadians(4.0)
        /** Sobel response that counts as an edge: a step of six or so gray levels. */
        private const val GRAD_MIN = 20.0
        /** Every side must run along an edge for this share of its length, on average this much. */
        private const val SIDE_MIN = 0.55
        private const val MEAN_MIN = 0.72
        /** A side running along an edge for this share of its length is sure of itself. */
        private const val SURE_SIDE = 0.9
        /** How much brighter, on average, filling in a counter's flecks makes it, to call it flecked. */
        private const val FLECK_GAIN = 4.0
        /** The cross-section of a side is read this many pixels either way. */
        private const val STEP_REACH = 7
        /** A step changes this share of its contrast within two pixels; a ramp doesn't. */
        private const val STEEP = 0.4
        private const val MIN_CONTRAST = 18.0
        /** A fainter step counts when it's this sharp. */
        private const val FAINT_CONTRAST = 10.0
        private const val FAINT_STEEP = 0.6
        /** An inner sheet this well outlined, at most this share of an outer one, splits it. */
        private const val WHOLE = 0.9
        private const val UNION_SHARE = 0.85
        /** Up to this share, an inner sheet still wins if the band beyond it is this much darker than it. */
        private const val BAND_SHARE = 0.97
        private const val BAND_DARKER = 0.92
        /** Paper darker than its background is rarer, so it needs the stronger outline. */
        private const val DARK_PAGE = 0.8
        private const val REFINE_REACH = 4
        private const val MAX_SNAP = 8.0
        /** Share of the frame counted as "on the edge". */
        private const val BORDER = 0.015f
        /** How far corners move toward the center, as a share of their distance to it. */
        private const val INSET = 0.012f
        /** Inner corners near outer ones, both in normalized frame units. */
        private const val SHARED_CORNER = 0.03
        private const val OUTSIDE = 0.02

        /** True when [inner] lies inside [outer] and has two adjacent corners on outer's. */
        private fun sharesSide(inner: Quad, outer: Quad): Boolean {
            val o = outer.corners
            val orientation = if (QuadGeometry.signedArea(o) >= 0) 1.0 else -1.0
            val inside = inner.corners.all { p ->
                (0 until 4).all { i ->
                    val a = o[i]
                    val b = o[(i + 1) % 4]
                    val cross = (b.x - a.x).toDouble() * (p.y - a.y) - (b.y - a.y).toDouble() * (p.x - a.x)
                    orientation * cross / hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) >= -OUTSIDE
                }
            }
            if (!inside) return false
            val near = inner.corners.map { c -> o.any { hypot((c.x - it.x).toDouble(), (c.y - it.y).toDouble()) < SHARED_CORNER } }
            return (0 until 4).any { near[it] && near[(it + 1) % 4] }
        }
    }
}
