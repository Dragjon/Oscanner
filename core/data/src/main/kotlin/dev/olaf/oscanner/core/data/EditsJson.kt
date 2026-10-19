package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.InkMark
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.Quad
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
        .toString()

    private fun writeMark(mark: InkMark) = JSONObject()
        .put("color", mark.color)
        .put("width", mark.width.toDouble())
        .put("highlighter", mark.highlighter)
        .put("points", JSONArray().apply { mark.points.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })

    private fun readMark(obj: JSONObject): InkMark? {
        val raw = obj.optJSONArray("points") ?: return null
        val points = List(raw.length() / 2) { Corner(raw.optDouble(it * 2).toFloat(), raw.optDouble(it * 2 + 1).toFloat()) }
            .filter { !it.x.isNaN() && !it.y.isNaN() }
        if (points.isEmpty()) return null
        return InkMark(points, obj.optInt("color", 0xFF000000.toInt()), obj.optDouble("width", 0.004).toFloat(), obj.optBoolean("highlighter"))
    }

    fun read(json: String): PageEdits {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return PageEdits()
        val quad = obj.optJSONArray("quad")
            ?.takeIf { it.length() == 8 }
            ?.let { a -> Quad.of(List(4) { Corner(a.optDouble(it * 2).toFloat(), a.optDouble(it * 2 + 1).toFloat()) }) }
            ?.takeIf { q -> q.corners.all { !it.x.isNaN() && !it.y.isNaN() } }
            ?: Quad.Full
        val filter = PageFilter.entries.firstOrNull { it.name == obj.optString("filter") } ?: PageFilter.Auto
        val rotation = obj.optInt("rotation", 0).let { ((it % 360) + 360) % 360 / 90 * 90 }
        fun unit(name: String, min: Float) = obj.optDouble(name, 0.0).toFloat().takeUnless { it.isNaN() }?.coerceIn(min, 1f) ?: 0f
        val aspect = obj.optDouble("aspect", Double.NaN).toFloat().takeIf { it.isFinite() && it > 0f }
        val marks = obj.optJSONArray("marks")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::readMark) } }.orEmpty()
        return PageEdits(quad, filter, rotation, unit("brightness", -1f), unit("contrast", -1f), unit("detail", 0f), aspect, marks)
    }
}
