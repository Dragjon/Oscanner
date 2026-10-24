package dev.olaf.oscanner.core.translate

import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage

/**
 * Which language a text is in, by Android's own on-device language finder (Android 10 and
 * later): one of [TranslationModels]' codes, or null when it can't tell or isn't sure.
 * Slow enough to keep off the main thread.
 */
object LanguageGuess {

    fun of(context: Context, text: String, known: Set<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text.isBlank()) return null
        return runCatching {
            val classifier = context.getSystemService(TextClassificationManager::class.java)?.textClassifier ?: return null
            val result = classifier.detectLanguage(TextLanguage.Request.Builder(text.take(SAMPLE)).build())
            if (result.localeHypothesisCount == 0) return null
            val locale = result.getLocale(0)
            if (result.getConfidenceScore(locale) < SURE) return null
            val code = when (locale.language) {
                // Chinese models come by script; traditional characters are Hant.
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
                "no" -> "nb"
                else -> locale.language
            }
            code.takeIf { it in known }
        }.getOrNull()
    }

    private const val SAMPLE = 2000
    private const val SURE = 0.5f
}
