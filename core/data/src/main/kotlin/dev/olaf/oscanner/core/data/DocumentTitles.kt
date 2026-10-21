package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PageLayout
import dev.olaf.oscanner.core.pdf.ParagraphStyle
import dev.olaf.oscanner.core.pdf.PdfWord

/**
 * A name for a document from what its first page says: its heading, or failing that a short
 * first line near the top ("Invoice 2041", "Dear Sam,"). Null when nothing reads like a title,
 * and the document keeps the name it was given.
 */
object DocumentTitles {

    fun fromWords(words: List<PdfWord>): String? {
        val paragraphs = PageLayout.paragraphs(words)
        val heading = paragraphs.firstOrNull { it.top < HEADING_ZONE && it.style != ParagraphStyle.Body }
        val firstShort = paragraphs.firstOrNull()?.takeIf { it.top < FIRST_LINE_ZONE && it.text.split(' ').size <= SHORT_WORDS }
        val text = (heading ?: firstShort)?.text ?: return null
        return tidy(text)
    }

    /** Trimmed to a title's length on a word boundary, without trailing punctuation; null if it isn't words. */
    internal fun tidy(text: String): String? {
        var t = text.trim().trimEnd(',', ';', ':', '.', '-', '–', '—').trim()
        if (t.length > MAX_LENGTH) t = t.take(MAX_LENGTH).substringBeforeLast(' ').trimEnd(',', ';', ':', '-') + "…"
        val letters = t.count(Char::isLetter)
        // "12/03/2025" or "#####" isn't a name; three letters and mostly letters is.
        return t.takeIf { letters >= MIN_LETTERS && letters >= t.count { !it.isWhitespace() } * LETTER_SHARE }
    }

    /** A heading counts in the top this share of the page; a plain first line, nearer the top. */
    private const val HEADING_ZONE = 0.4f
    private const val FIRST_LINE_ZONE = 0.25f
    private const val SHORT_WORDS = 8
    private const val MAX_LENGTH = 60
    private const val MIN_LETTERS = 3
    private const val LETTER_SHARE = 0.5
}
