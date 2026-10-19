package dev.olaf.oscanner.core.data

import android.content.Context
import dev.olaf.oscanner.core.model.Corner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A drawn signature: strokes normalized to its own bounding box (0…1 on each axis), plus that
 * box's width ÷ height, so it can be placed at any size without distortion.
 */
data class Signature(val id: String, val strokes: List<List<Corner>>, val aspect: Float)

/** Signatures the user chose to keep, in `files/signatures.json`. Never leaves the phone. */
class SignatureStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, "signatures.json")
    private val _signatures = MutableStateFlow<List<Signature>>(emptyList())
    val signatures: StateFlow<List<Signature>> = _signatures.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        _signatures.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    suspend fun save(strokes: List<List<Corner>>, aspect: Float): Signature {
        val signature = Signature(UUID.randomUUID().toString(), strokes, aspect)
        // The newest first; a handful is plenty.
        write((listOf(signature) + _signatures.value).take(MAX))
        return signature
    }

    suspend fun delete(id: String) = write(_signatures.value.filter { it.id != id })

    private suspend fun write(list: List<Signature>) = withContext(Dispatchers.IO) {
        val json = JSONArray()
        list.forEach { sig ->
            json.put(
                JSONObject()
                    .put("id", sig.id)
                    .put("aspect", sig.aspect.toDouble())
                    .put("strokes", JSONArray().apply { sig.strokes.forEach { s -> put(JSONArray().apply { s.forEach { put(it.x.toDouble()); put(it.y.toDouble()) } }) } }),
            )
        }
        file.writeText(json.toString())
        _signatures.value = list
    }

    private fun parse(text: String): List<Signature> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val strokes = obj.optJSONArray("strokes") ?: return@mapNotNull null
            Signature(
                id = obj.optString("id"),
                aspect = obj.optDouble("aspect", 3.0).toFloat(),
                strokes = (0 until strokes.length()).map { s ->
                    val raw = strokes.getJSONArray(s)
                    List(raw.length() / 2) { Corner(raw.getDouble(it * 2).toFloat(), raw.getDouble(it * 2 + 1).toFloat()) }
                },
            )
        }
    }

    private companion object {
        const val MAX = 5
    }
}
