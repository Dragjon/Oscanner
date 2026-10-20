package dev.olaf.oscanner.core.ocr

import android.content.res.AssetManager
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.log10
import kotlin.math.max

/** One letter as read, with the reader's runners-up at that spot, most likely first. */
data class LetterGuess(val options: List<Pair<Char, Float>>) {
    val best: Char get() = options.first().first
    val confidence: Float get() = options.first().second
}

/**
 * Fixes misread English words by weighing the reader's own doubts against how people write.
 * Where the reader was unsure of a letter, its next-best guesses for that spot are tried;
 * each spelling is scored by how sure the reader was of it plus how common the word is and
 * how often it follows and precedes its neighbors (word and word-pair counts from Google
 * Books, via SymSpell). Words the reader was sure of, numbers, names in capitals and codes
 * are left as read, and a real word only gives way to a clearly likelier one.
 *
 * Holds about 10 MB of counts; share one per process.
 */
class WordFixer private constructor(
    private val index: Map<String, Int>,
    private val counts: LongArray,
    private val pairKeys: LongArray,
    private val pairCounts: IntArray,
) {
    private val total = counts.sum().toDouble()
    private val unseen = log10(1.0 / total) - 1

    /** [words] are one line's words, as letter guesses, in order; returns them corrected. */
    fun fix(words: List<List<LetterGuess>>): List<String> {
        val plain = words.map { word -> word.map { it.best }.joinToString("") }
        return words.mapIndexed { i, word -> fixWord(word, plain.getOrNull(i - 1), plain.getOrNull(i + 1)) }
    }

    private fun fixWord(letters: List<LetterGuess>, before: String?, after: String?): String {
        val word = letters.map { it.best }.joinToString("")
        // Only the run of letters in the middle is looked up; quotes, commas and the like
        // around it stay as read.
        val start = letters.indexOfFirst { it.best.isLetter() }
        val end = letters.indexOfLast { it.best.isLetter() }
        if (start < 0) return word
        val core = letters.subList(start, end + 1)
        if (core.size < MIN_LENGTH || core.any { !it.best.isLetter() }) return word
        val doubtful = core.indices.filter { core[it].confidence < DOUBT }.take(MAX_DOUBTFUL)
        if (doubtful.isEmpty()) return word
        // Codes and acronyms (several capitals) aren't words to look up.
        if (core.count { it.best.isUpperCase() } > 1) return word

        val prev = before?.letters()
        val next = after?.letters()
        val original = core.map { it.best }.joinToString("")
        val readOdds = core.sumOf { log10(max(it.confidence, FLOOR).toDouble()) }
        var best = original
        var bar = score(original, readOdds, prev, next) + if (original.lowercase() in index) MARGIN else 0.0
        var spellings = listOf("" to 0.0)
        core.forEachIndexed { i, letter ->
            val options = if (i in doubtful) letter.options.take(ALTERNATIVES) else letter.options.take(1)
            spellings = spellings.flatMap { (text, odds) ->
                options.filter { it.first.isLetter() }.map { (ch, p) -> text + ch to odds + log10(max(p, FLOOR).toDouble()) }
            }
        }
        for ((spelling, odds) in spellings) {
            if (spelling == original) continue
            val s = score(spelling, odds, prev, next)
            if (s > bar) {
                best = spelling
                bar = s
            }
        }
        if (best == original) return word
        val prefix = letters.subList(0, start).map { it.best }.joinToString("")
        val suffix = letters.subList(end + 1, letters.size).map { it.best }.joinToString("")
        return prefix + best + suffix
    }

    private fun score(spelling: String, readOdds: Double, prev: String?, next: String?): Double =
        readOdds + wordOdds(spelling) + pairBonus(prev, spelling) + pairBonus(spelling, next)

    private fun wordOdds(word: String): Double =
        index[word.lowercase()]?.let { log10(counts[it] / total) } ?: unseen

    /** How much likelier [b] is right after [a] than on its own; 0 when the pair isn't known. */
    private fun pairBonus(a: String?, b: String?): Double {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return 0.0
        val ia = index[a.lowercase()] ?: return 0.0
        val ib = index[b.lowercase()] ?: return 0.0
        val at = pairKeys.binarySearch((ia.toLong() shl 32) or ib.toLong())
        if (at < 0) return 0.0
        return max(0.0, log10(pairCounts[at] * total / (counts[ia].toDouble() * counts[ib])))
    }

    private fun String.letters() = filter { it.isLetter() }.ifEmpty { null }

    companion object {
        /** Below this, a letter is open to question. */
        private const val DOUBT = 0.9f
        private const val MIN_LENGTH = 3
        private const val MAX_DOUBTFUL = 3
        private const val ALTERNATIVES = 3
        /** In powers of ten: how much likelier a different spelling must be than a real word. */
        private const val MARGIN = 1.5
        private const val FLOOR = 1e-6f

        @Volatile private var shared: WordFixer? = null

        /** Loads the word counts from assets once; later calls share them. */
        fun get(assets: AssetManager): WordFixer = shared ?: synchronized(this) {
            shared ?: load(assets.open("paddle/words.dat"), assets.open("paddle/pairs.dat")).also { shared = it }
        }

        /** [words]: gzipped "word count" lines. [pairs]: gzipped count, then sorted key and count records.
         * (Named .dat: the Android build would strip a .gz name.) */
        internal fun load(words: InputStream, pairs: InputStream): WordFixer {
            val index = HashMap<String, Int>(120_000)
            val counts = ArrayList<Long>(90_000)
            GZIPInputStream(words).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val space = line.indexOf(' ')
                    if (space > 0) {
                        index[line.substring(0, space)] = counts.size
                        counts += line.substring(space + 1).toLong()
                    }
                }
            }
            val (keys, pairCounts) = DataInputStream(GZIPInputStream(pairs).buffered()).use { input ->
                val n = input.readInt()
                val keys = LongArray(n)
                val values = IntArray(n)
                for (i in 0 until n) {
                    keys[i] = input.readLong()
                    values[i] = input.readInt()
                }
                keys to values
            }
            return WordFixer(index, counts.toLongArray(), keys, pairCounts)
        }
    }
}
