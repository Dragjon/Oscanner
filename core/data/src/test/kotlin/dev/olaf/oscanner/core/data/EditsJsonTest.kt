package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.model.CurveLine
import dev.olaf.oscanner.core.model.PageCurve
import dev.olaf.oscanner.core.model.PageEdits
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditsJsonTest {

    private val squeezed = PageEdits(
        flatten = true,
        curve = PageCurve(
            listOf(CurveLine(listOf(0.2f, 0f, 0f, 0.03f), 0.05f, 0.95f), CurveLine(listOf(0.7f, 0f, 0f, 0.05f), 0.05f, 0.95f)),
            columns = listOf(0f, 0.3f, 0.62f, 0.85f, 1f),
            widen = 1.04f,
        ),
    )

    @Test
    fun aSqueezeIsKept() {
        assertEquals(squeezed.curve, EditsJson.read(EditsJson.write(squeezed)).curve)
    }

    @Test
    fun aBendMeasuredBeforeSqueezesWereIsMeasuredAgain() {
        val old = JSONObject(EditsJson.write(squeezed)).apply {
            remove("columns")
            remove("widen")
        }
        assertNull(EditsJson.read(old.toString()).curve)
    }

    @Test
    fun columnsOutOfOrderAreDropped() {
        val bad = JSONObject(EditsJson.write(squeezed)).apply { put("columns", org.json.JSONArray(listOf(0.0, 0.6, 0.4, 1.0))) }
        val curve = EditsJson.read(bad.toString()).curve!!
        assertEquals(2, curve.lines.size)
        assertEquals(emptyList<Float>(), curve.columns)
        assertEquals(1f, curve.widen)
    }
}
