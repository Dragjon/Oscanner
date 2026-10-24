package dev.olaf.oscanner.core.translate

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A language that can be translated to and from, and whether its models are on the phone. */
data class TranslationLanguage(val code: String, val name: String, val installed: Boolean, val sizeBytes: Long)

/** One downloaded direction: its folder and its files by type (model, lex, vocab or srcvocab and trgvocab). */
data class InstalledModel(val from: String, val to: String, val dir: File, val files: Map<String, String>)

/**
 * Mozilla's translation models (MPL-2.0, made for Firefox Translations), downloaded on request:
 * each language goes both ways with English, and other pairs go through English. Which files
 * exist, and their hashes, comes with the app (assets/translation/models.json, written by
 * tools/translation-models/catalog.py); every download is checked against it.
 */
class TranslationModels(context: Context) {

    private val appContext = context.applicationContext
    /** Where downloaded models live, one folder per direction. */
    val root = File(appContext.filesDir, "translation")
    private val prefs = appContext.getSharedPreferences("translation", Context.MODE_PRIVATE)

    /** The language last translated into, offered first next time. */
    var lastTarget: String?
        get() = prefs.getString("to", null)
        set(value) = prefs.edit().putString("to", value).apply()

    /** Every language code there are models for, English included. */
    val codes: Set<String> get() = pairs.flatMap { listOf(it.from, it.to) }.toSet()

    private class Pair(val from: String, val to: String, val version: String, val files: List<ModelFile>) {
        val folder get() = "$from-$to"
    }

    private class ModelFile(val type: String, val name: String, val url: String, val size: Long, val sha256: String)

    private val pairs: List<Pair> by lazy {
        val json = JSONObject(appContext.assets.open("translation/models.json").bufferedReader().use { it.readText() })
        val list = json.getJSONArray("pairs")
        List(list.length()) { i ->
            val p = list.getJSONObject(i)
            val files = p.getJSONArray("files")
            Pair(
                p.getString("from"), p.getString("to"), p.getString("version"),
                List(files.length()) { j ->
                    val f = files.getJSONObject(j)
                    ModelFile(f.getString("type"), f.getString("name"), f.getString("url"), f.getLong("size"), f.getString("sha256"))
                },
            )
        }
    }

    private fun pair(from: String, to: String) = pairs.firstOrNull { it.from == from && it.to == to }

    /** Both directions with English, as far as they exist. */
    private fun pairsFor(code: String) = listOfNotNull(pair(code, ENGLISH), pair(ENGLISH, code))

    private fun isInstalled(p: Pair) = File(File(root, p.folder), DONE).readTextOrNull() == p.version

    fun isInstalled(code: String) = code == ENGLISH || pairsFor(code).let { it.isNotEmpty() && it.all(::isInstalled) }

    /** Every language there are models for, English first, the rest by name in the phone's language. */
    fun languages(): List<TranslationLanguage> {
        val codes = (pairs.map { it.from } + pairs.map { it.to }).toSet() - ENGLISH
        val locale = Locale.getDefault()
        return listOf(TranslationLanguage(ENGLISH, displayName(ENGLISH, locale), installed = true, sizeBytes = 0)) +
            codes.map { code ->
                TranslationLanguage(code, displayName(code, locale), isInstalled(code), pairsFor(code).sumOf { p -> p.files.sumOf { it.size } })
            }.sortedBy { it.name.lowercase(locale) }
    }

    /**
     * The models that take [from] to [to]: one direction, or two through English. Null when
     * one of them isn't downloaded.
     */
    fun route(from: String, to: String): List<InstalledModel>? {
        val steps = if (from == ENGLISH || to == ENGLISH) listOf(from to to) else listOf(from to ENGLISH, ENGLISH to to)
        return steps.map { (a, b) ->
            val p = pair(a, b)?.takeIf(::isInstalled) ?: return null
            InstalledModel(a, b, File(root, p.folder), p.files.associate { it.type to it.name })
        }
    }

    /**
     * Downloads both directions between [code] and English. [onProgress] gets 0…1. Each file
     * is checked against its size and hash; a direction counts as there only once all its
     * files are.
     */
    suspend fun download(code: String, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val wanted = pairsFor(code).filterNot(::isInstalled)
        require(pairsFor(code).isNotEmpty()) { "No models for $code" }
        val total = wanted.sumOf { p -> p.files.sumOf { it.size } }.coerceAtLeast(1)
        var done = 0L
        for (p in wanted) {
            val dir = File(root, p.folder).apply { mkdirs() }
            File(dir, DONE).delete()
            for (f in p.files) {
                fetch(f, File(dir, f.name)) { read -> onProgress(((done + read).toFloat() / total).coerceAtMost(1f)) }
                done += f.size
            }
            File(dir, DONE).writeText(p.version)
        }
        onProgress(1f)
    }

    /** Removes [code]'s models. */
    fun delete(code: String) {
        pairsFor(code).forEach { File(root, it.folder).deleteRecursively() }
    }

    private suspend fun fetch(file: ModelFile, target: File, onRead: (Long) -> Unit) {
        // Already here from an interrupted download of the same version.
        if (target.length() == file.size && sha256(target) == file.sha256) return
        val temp = File(target.path + ".part")
        val connection = URL(file.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Download failed: HTTP ${connection.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        onRead(read)
                    }
                }
            }
            check(read == file.size && hex(digest.digest()) == file.sha256) { "Download of ${file.name} is damaged" }
            check(temp.renameTo(target) || (target.delete() && temp.renameTo(target))) { "Can't save ${file.name}" }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun File.readTextOrNull() = takeIf { it.isFile }?.readText()

    companion object {
        const val ENGLISH = "en"
        /** Written last into a direction's folder, with its version: the download is whole. */
        private const val DONE = "complete"

        /** "zh-Hans" is Chinese (Simplified), in [locale]'s words. */
        fun displayName(code: String, locale: Locale): String {
            val language = Locale.forLanguageTag(code)
            val name = language.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
            // Chinese comes in its two scripts, which Android names "Simplified Han" and "Traditional Han".
            return when (language.script) {
                "Hans" -> "$name (Simplified)"
                "Hant" -> "$name (Traditional)"
                else -> language.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
            }
        }
    }
}
