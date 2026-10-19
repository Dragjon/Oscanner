package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.model.Corner
import dev.olaf.oscanner.core.model.PageEdits
import dev.olaf.oscanner.core.model.PageFilter
import dev.olaf.oscanner.core.model.Quad
import org.json.JSONArray
import org.json.JSONObject

/**
 * `{"v":1,"quad":[x0,y0,…,x3,y3],"filter":"Auto","rotation":0}`. Unknown or broken JSON reads
 * as default edits, so a bad recipe never makes a page unopenable.
 */
internal object EditsJson {

    fun write(edits: PageEdits): String = JSONObject()
        .put("v", 1)
        .put("quad", JSONArray().apply { edits.quad.corners.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } })
        .put("filter", edits.filter.name)
        .put("rotation", edits.rotation)
        .toString()

    fun read(json: String): PageEdits {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return PageEdits()
        val quad = obj.optJSONArray("quad")
            ?.takeIf { it.length() == 8 }
            ?.let { a -> Quad.of(List(4) { Corner(a.optDouble(it * 2).toFloat(), a.optDouble(it * 2 + 1).toFloat()) }) }
            ?.takeIf { q -> q.corners.all { !it.x.isNaN() && !it.y.isNaN() } }
            ?: Quad.Full
        val filter = PageFilter.entries.firstOrNull { it.name == obj.optString("filter") } ?: PageFilter.Auto
        val rotation = obj.optInt("rotation", 0).let { ((it % 360) + 360) % 360 / 90 * 90 }
        return PageEdits(quad, filter, rotation)
    }
}
