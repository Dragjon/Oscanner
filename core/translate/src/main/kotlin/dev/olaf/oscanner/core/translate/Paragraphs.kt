package dev.olaf.oscanner.core.translate

/**
 * Read text breaks lines where the page did, mid-sentence; a translation needs whole
 * sentences. Lines join into paragraphs until a blank line, or a line that stops well short of
 * the others (a paragraph's last line, a heading, an item in a list). A word hyphenated across
 * a line end is mended.
 */
object Paragraphs {

    fun of(text: String): List<String> {
        val lines = text.lines().map { it.trim() }
        val full = lines.filter { it.isNotEmpty() }.map { it.length }.sorted().let { lengths ->
            // The length of a full line: most lines of a paragraph are about as long as this.
            if (lengths.isEmpty()) 0 else lengths[(lengths.size * FULL_LINE).toInt().coerceAtMost(lengths.lastIndex)]
        }
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun end() {
            if (current.isNotEmpty()) paragraphs += current.toString()
            current.clear()
        }
        for (line in lines) {
            if (line.isEmpty()) {
                end()
                continue
            }
            // A list item or a numbered point starts afresh.
            if (current.isNotEmpty() && BULLET.containsMatchIn(line)) end()
            // A short line after a finished sentence stands alone ("Yours sincerely" after a
            // paragraph whose last line happened to be full).
            if (current.isNotEmpty() && current.last() in ENDS && line.length < full * SHORT) end()
            when {
                current.isEmpty() -> current.append(line)
                current.endsWith('-') && current.length > 1 && current[current.length - 2].isLetter() && line.first().isLowerCase() ->
                    current.setLength(current.length - 1).also { current.append(line) }
                else -> current.append(' ').append(line)
            }
            // A word broken across the line end carries on, however short the line.
            val broken = line.length > 1 && line.endsWith('-') && line[line.length - 2].isLetter()
            if (line.length < full * SHORT && !broken) end()
        }
        end()
        return paragraphs
    }

    /** The line length that 80% of lines reach or fall short of stands for a full line. */
    private const val FULL_LINE = 0.8
    /** A line under this share of a full one ends its paragraph. */
    private const val SHORT = 0.7
    private val ENDS = setOf('.', '!', '?', ':', '。', '！', '？')
    private val BULLET =Regex("""^(?:[-•*·▪◦]|\d{1,3}[.)]|[a-z][.)]|\(\w{1,3}\))\s""")
}
