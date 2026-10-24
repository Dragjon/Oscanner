package dev.olaf.oscanner.core.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A page's text put [from] one language [to] another, and [onPage], the translation of each
 * paragraph as it sits on the page (see [PageStore.paragraphsOnPage]) to set over the picture.
 * It belongs to the text it was made from, so fixing the page's text leaves it behind: [read]
 * then finds nothing.
 */
data class PageTranslation(val from: String, val to: String, val text: String, val onPage: List<String> = emptyList()) {

    companion object {
        /** Kept beside the page's other files, so it goes wherever they go. */
        private const val FILE = "translation.json"

        fun file(originalPath: String) = File(File(originalPath).parentFile, FILE)

        /** The translation saved for [source] at [file], if there is one and it still fits. */
        fun read(file: File, source: String): PageTranslation? = runCatching {
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            if (json.optString("source") != digest(source)) return null
            val onPage = json.optJSONArray("onPage")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            PageTranslation(json.getString("from"), json.getString("to"), json.getString("text"), onPage)
        }.getOrNull()

        fun write(file: File, source: String, translation: PageTranslation) {
            val json = JSONObject().put("v", 1).put("from", translation.from).put("to", translation.to)
                .put("source", digest(source)).put("text", translation.text).put("onPage", JSONArray(translation.onPage))
            val part = File(file.parentFile, "$FILE.part")
            part.writeText(json.toString())
            Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
