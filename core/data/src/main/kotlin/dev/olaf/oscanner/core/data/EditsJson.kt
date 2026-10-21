package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.model.Area
import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.LineStyle
import dev.olaf.oscanner.core.model.TextFont
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.Quad
import dev.olaf.oscanner.core.model.TextMark
import org.json.JSONArray
import org.json.JSONObject

/**
 * `{"v":1,"quad":[x0,y0,…,x3,y3],"filter":"Auto","rotation":0,"brightness":0,"contrast":0,"detail":0}`.
 * Missing fields read as their defaults. Unknown or broken JSON reads
 * as default edits, so a bad recipe never makes a page unopenable.
 */
internal object EditsJson {

    fun write(edits: PageEdits): String = JSONObject()
        .put("v", 1)
        .put("quad", JSONArray().apply { edits.quad.corners.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
        .put("filter", edits.filter.name)
        .put("rotation", edits.rotation)
        .put("brightness", edits.brightness.toDouble())
        .put("contrast", edits.contrast.toDouble())
        .put("detail", edits.detail.toDouble())
        .apply { edits.aspect?.let { put("aspect", it.toDouble()) } }
        .apply { if (edits.marks.isNotEmpty()) put("marks", JSONArray().apply { edits.marks.forEach { put(writeMark(it)) } }) }
        .apply { if (edits.texts.isNotEmpty()) put("texts", JSONArray().apply { edits.texts.forEach { put(writeText(it)) } }) }
        .apply { if (edits.erase.isNotEmpty()) put("erase", JSONArray().apply { edits.erase.forEach { put(writeMark(it)) } }) }
        .apply { if (edits.keep.isNotEmpty()) put("keep", JSONArray().apply { edits.keep.forEach { put(writeMark(it)) } }) }
        .apply { if (edits.removePenInk) put("removePenInk", true) }
        .apply { if (edits.removeFingers) put("removeFingers", true) }
        .apply { if (edits.removeShowThrough) put("removeShowThrough", true) }
        .apply {
            edits.printed?.let { areas ->
                put("printed", JSONArray().apply { areas.forEach { put(it.left.toDouble()); put(it.top.toDouble()); put(it.right.toDouble()); put(it.bottom.toDouble()) } })
                if (edits.printByLook) put("printByLook", true)
            }
        }
        .apply { if (edits.flatten) put("flatten", true) }
        .apply { if (edits.descreen) put("descreen", true) }
        .apply {
            edits.curve?.let { curve ->
                put("bend", JSONArray().apply { curve.lines.forEach { put(writeLine(it)) } })
            }
        }
        .apply {
            if (edits.redactions.isNotEmpty()) {
                put("redact", JSONArray().apply { edits.redactions.forEach { put(writePoints(it)) } })
            }
        }
        .toString()

    private fun writeText(mark: TextMark) = JSONObject()
        .put("text", mark.text)
        .put("x", mark.x.toDouble())
        .put("y", mark.y.toDouble())
        .put("size", mark.size.toDouble())
        .put("color", mark.color)
        .put("angle", mark.angle)
        .put("stamp", mark.stamp)
        .apply { if (mark.font != TextFont.Sans) put("font", mark.font.name) }
        .apply { if (mark.bold) put("bold", true) }
        .apply { mark.background?.let { put("background", it) } }

    private fun readText(obj: JSONObject): TextMark? {
        val text = obj.optString("text").takeIf { it.isNotBlank() } ?: return null
        val x = obj.optDouble("x", Double.NaN).toFloat()
        val y = obj.optDouble("y", Double.NaN).toFloat()
        val size = obj.optDouble("size", Double.NaN).toFloat()
        if (!x.isFinite() || !y.isFinite() || !size.isFinite() || size <= 0f) return null
        val angle = obj.optInt("angle", 0).let { ((it % 360) + 360) % 360 / 90 * 90 }
        return TextMark(
            text, x, y, size, obj.optInt("color", 0xFF000000.toInt()), angle, obj.optBoolean("stamp"),
            font = TextFont.entries.firstOrNull { it.name == obj.optString("font") } ?: TextFont.Sans,
            bold = obj.optBoolean("bold"),
            background = if (obj.has("background")) obj.optInt("background") else null,
        )
    }

    private fun writeLine(line: CurveLine) = JSONArray().apply {
        line.coefficients.forEach { put(it.toDouble()) }
        put(line.from.toDouble())
        put(line.to.toDouble())
    }

    private fun readLine(a: JSONArray?): CurveLine? {
        if (a == null || a.length() != 6) return null
        val values = List(6) { a.optDouble(it).toFloat() }
        if (values.any { !it.isFinite() }) return null
        return CurveLine(values.take(4), values[4], values[5])
    }

    private fun writePoints(points: List<Corner>) = JSONArray().apply { points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }

    /** x0, y0, x1, y1, …; null unless there are at least [min] points, all numbers. */
    private fun readPoints(a: JSONArray?, min: Int): List<Corner>? = a
        ?.takeIf { it.length() >= min * 2 && it.length() % 2 == 0 }
        ?.let { List(it.length() / 2) { i -> Corner(a.optDouble(i * 2).toFloat(), a.optDouble(i * 2 + 1).toFloat()) } }
        ?.takeIf { points -> points.all { !it.x.isNaN() && !it.y.isNaN() } }

    private fun readQuad(a: JSONArray?): Quad? = readPoints(a, 4)?.takeIf { it.size == 4 }?.let(Quad::of)

    private fun writeMark(mark: InkMark) = JSONObject()
        .put("color", mark.color)
        .put("width", mark.width.toDouble())
        .put("highlighter", mark.highlighter)
        .apply { if (mark.style != LineStyle.Solid) put("style", mark.style.name) }
        .put("points", JSONArray().apply { mark.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })

    private fun readMark(obj: JSONObject): InkMark? {
        val raw = obj.optJSONArray("points") ?: return null
        val points = List(raw.length() / 2) { Corner(raw.optDouble(it * 2).toFloat(), raw.optDouble(it * 2 + 1).toFloat()) }
            .filter { !it.x.isNaN() && !it.y.isNaN() }
        if (points.isEmpty()) return null
        return InkMark(
            points, obj.optInt("color", 0xFF000000.toInt()), obj.optDouble("width", 0.004).toFloat(), obj.optBoolean("highlighter"),
            style = LineStyle.entries.firstOrNull { it.name == obj.optString("style") } ?: LineStyle.Solid,
        )
    }

    fun read(json: String): PageEdits {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return PageEdits()
        val quad = readQuad(obj.optJSONArray("quad")) ?: Quad.Full
        val filter = PageFilter.entries.firstOrNull { it.name == obj.optString("filter") } ?: PageFilter.Auto
        val rotation = obj.optInt("rotation", 0).let { ((it % 360) + 360) % 360 / 90 * 90 }
        fun unit(name: String, min: Float) = obj.optDouble(name, 0.0).toFloat().takeUnless { it.isNaN() }?.coerceIn(min, 1f) ?: 0f
        val aspect = obj.optDouble("aspect", Double.NaN).toFloat().takeIf { it.isFinite() && it > 0f }
        fun strokes(name: String) =
            obj.optJSONArray(name)?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::readMark) } }.orEmpty()
        return PageEdits(
            quad = quad,
            filter = filter,
            rotation = rotation,
            brightness = unit("brightness", -1f),
            contrast = unit("contrast", -1f),
            detail = unit("detail", 0f),
            aspect = aspect,
            marks = strokes("marks"),
            texts = obj.optJSONArray("texts")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::readText) } }.orEmpty(),
            erase = strokes("erase"),
            keep = strokes("keep"),
            removePenInk = obj.optBoolean("removePenInk"),
            removeFingers = obj.optBoolean("removeFingers"),
            removeShowThrough = obj.optBoolean("removeShowThrough"),
            printByLook = obj.optBoolean("printByLook"),
            printed = obj.optJSONArray("printed")?.let { a ->
                List(a.length() / 4) { i -> Area(a.optDouble(i * 4).toFloat(), a.optDouble(i * 4 + 1).toFloat(), a.optDouble(i * 4 + 2).toFloat(), a.optDouble(i * 4 + 3).toFloat()) }
                    .filter { listOf(it.left, it.top, it.right, it.bottom).none(Float::isNaN) }
            },
            flatten = obj.optBoolean("flatten"),
            descreen = obj.optBoolean("descreen"),
            // "curve" held bends measured the old way, which made pages worse: left unread, they're
            // measured again.
            curve = obj.optJSONArray("bend")?.let { a ->
                // All lines or none: a partial bend would distort the page.
                val lines = (0 until a.length()).map { readLine(a.optJSONArray(it)) }
                PageCurve(if (lines.all { it != null }) lines.filterNotNull() else emptyList())
            },
            redactions = obj.optJSONArray("redact")?.let { a -> (0 until a.length()).mapNotNull { readPoints(a.optJSONArray(it), 3) } }.orEmpty(),
        )
    }
}
