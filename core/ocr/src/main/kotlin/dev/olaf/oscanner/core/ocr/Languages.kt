package dev.olaf.oscanner.core.ocr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A language Tesseract can read, by its tessdata code. */
data class OcrLanguage(val code: String, val name: String, val installed: Boolean, val bundled: Boolean)

/**
 * Recognition models in `files/tessdata`. English ships in the APK; the others download only
 * when someone asks for them, from the official tessdata_fast repository (Apache-2.0). That
 * download is the only network request the app makes (PLAN.md §5.7).
 */
class Languages(context: Context) {

    private val appContext = context.applicationContext
    /** Tesseract wants the parent of the `tessdata` folder. */
    val dataPath: String = appContext.filesDir.path
    private val dir = File(appContext.filesDir, "tessdata")

    fun isInstalled(code: String) = File(dir, "$code.traineddata").let { it.exists() && it.length() > 0 } ||
        (code == ENGLISH)

    fun list(): List<OcrLanguage> {
        val locale = Locale.getDefault()
        return CATALOG.map { code ->
            OcrLanguage(code, displayName(code, locale), installed = isInstalled(code), bundled = code == ENGLISH)
        }.sortedWith(compareBy<OcrLanguage> { !it.bundled }.thenBy { it.name })
    }

    /** Copies the bundled English model out of the APK the first time it's needed. */
    @Synchronized
    fun ensureBundled() {
        val target = File(dir, "$ENGLISH.traineddata")
        if (target.exists() && target.length() > 0) return
        dir.mkdirs()
        val temp = File(dir, "$ENGLISH.traineddata.part")
        appContext.assets.open("tessdata/$ENGLISH.traineddata").use { input -> temp.outputStream().use { input.copyTo(it) } }
        temp.renameTo(target)
    }

    /** Downloads one model. [onProgress] gets 0…1 when the size is known. */
    suspend fun download(code: String, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        require(code in CATALOG) { "Unknown language $code" }
        dir.mkdirs()
        val temp = File(dir, "$code.traineddata.part")
        val connection = URL("$SOURCE/$code.traineddata").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Download failed: HTTP ${connection.responseCode}" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            check(temp.length() > 1024) { "Download was empty" }
            check(temp.renameTo(File(dir, "$code.traineddata"))) { "Couldn't save $code" }
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    fun remove(code: String) {
        if (code == ENGLISH) return
        File(dir, "$code.traineddata").delete()
    }

    private fun displayName(code: String, locale: Locale): String {
        val tag = TAGS[code] ?: code
        return Locale.forLanguageTag(tag).getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifBlank { code }
    }

    companion object {
        const val ENGLISH = "eng"
        private const val SOURCE = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"

        /** tessdata codes → BCP 47, for names in the user's language. */
        private val TAGS = mapOf(
            "eng" to "en", "deu" to "de", "fra" to "fr", "spa" to "es", "ita" to "it", "por" to "pt",
            "nld" to "nl", "pol" to "pl", "ces" to "cs", "slk" to "sk", "hun" to "hu", "ron" to "ro",
            "swe" to "sv", "dan" to "da", "nor" to "no", "fin" to "fi", "tur" to "tr", "ell" to "el",
            "rus" to "ru", "ukr" to "uk", "bul" to "bg", "srp" to "sr", "hrv" to "hr", "slv" to "sl",
            "ara" to "ar", "heb" to "he", "fas" to "fa", "hin" to "hi", "ben" to "bn", "tam" to "ta",
            "tha" to "th", "vie" to "vi", "ind" to "id", "msa" to "ms", "fil" to "fil",
            "chi_sim" to "zh-Hans", "chi_tra" to "zh-Hant", "jpn" to "ja", "kor" to "ko",
        )
        val CATALOG: List<String> = TAGS.keys.toList()
    }
}
