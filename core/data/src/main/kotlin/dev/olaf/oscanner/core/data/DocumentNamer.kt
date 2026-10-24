package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/**
 * Names a document the way a person would file it, from what its first page says: what kind
 * of paper it is, who it's from and when. "Receipt – Tesco – 24 Oct 2026", "Letter from HMRC –
 * 3 Mar 2026", "Invoice – Acme Plumbing – 12 Sept 2026". All on the phone, from rules rather
 * than a model: a few kinds of paper are most of what gets scanned, and each has words that
 * give it away. Pages it can't place are named after their heading ([DocumentTitles]).
 */
class DocumentNamer(
    /** Whether 03/04/2026 is the 4th of March, as in the US, rather than the 3rd of April. */
    private val monthFirst: Boolean = Locale.getDefault().country in MONTH_FIRST_COUNTRIES,
    private val formatDate: (LocalDate) -> String = { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it) },
) {

    enum class Kind(val label: String, internal val strong: List<String>, internal val weak: List<String> = emptyList()) {
        Receipt(
            "Receipt",
            listOf("receipt", "subtotal", "sub total", "sub-total", "change due", "thank you for shopping", "cashier", "contactless", "card payment", "items sold"),
            listOf("total", "cash", "change", "visa", "mastercard", "debit", "vat", "gst", "qty", "amount", "tender", "store"),
        ),
        Invoice(
            "Invoice",
            listOf("invoice", "bill to", "billed to", "amount due", "balance due", "payment terms", "due date", "remit to"),
            listOf("vat", "gst", "total", "qty", "quantity", "unit price", "description", "subtotal"),
        ),
        Quote(
            "Quote",
            listOf("quotation", "quote no", "quote number", "estimate", "valid until", "valid for"),
            listOf("total", "qty", "unit price", "description"),
        ),
        Statement(
            "Statement",
            listOf("statement", "opening balance", "closing balance", "account summary", "sort code", "iban", "balance brought forward", "previous balance"),
            listOf("account number", "balance", "transactions", "withdrawals", "deposits", "payments", "credits", "debits"),
        ),
        Payslip(
            "Payslip",
            listOf("payslip", "pay slip", "pay stub", "net pay", "gross pay", "pay period", "earnings", "deductions"),
            listOf("tax code", "national insurance", "ytd", "year to date", "salary", "employee", "employer"),
        ),
        Letter(
            "Letter",
            listOf("dear", "yours sincerely", "yours faithfully", "kind regards", "best regards", "warm regards", "sincerely"),
            listOf("re:", "subject:", "our ref", "your ref"),
        ),
        Contract(
            "Contract",
            listOf("agreement", "contract", "hereinafter", "the parties", "witnesseth", "terms and conditions", "tenancy", "lease"),
            listOf("signature", "signed", "clause", "party", "landlord", "tenant"),
        ),
        Ticket(
            "Ticket",
            listOf("boarding pass", "e-ticket", "eticket", "ticket", "admit one"),
            listOf("gate", "seat", "flight", "departure", "platform", "coach", "row", "boarding"),
        ),
        Prescription(
            "Prescription",
            listOf("prescription", "dosage", "pharmacy", "rx"),
            listOf("patient", "tablets", "mg", "doctor", "dr"),
        ),
        Certificate(
            "Certificate",
            listOf("certificate", "certify that", "certifies that", "awarded to", "in recognition"),
        ),
        Form(
            "Form",
            listOf("application form", "please complete", "please print", "for office use", "registration form", "claim form"),
            listOf("date of birth", "signature", "tick", "applicant"),
        ),
    }

    /** A name for the document whose first page has [words], or null to leave its name alone. */
    fun name(words: List<PdfWord>): String? {
        val rows = rows(words)
        if (rows.isEmpty()) return null
        val kind = kind(rows) ?: return DocumentTitles.fromWords(words)
        val date = date(rows)?.let(formatDate)
        val who = issuer(rows, kind)
        val heading = DocumentTitles.fromWords(words)?.takeUnless { bare(it, kind) }
        val name = when (kind) {
            Kind.Letter -> {
                val subject = subject(rows)
                when {
                    subject != null -> join("Letter", subject, date)
                    who != null -> join("Letter from $who", date)
                    else -> join("Letter", date)
                }
            }
            // These carry their own descriptive heading: "Tenancy Agreement", "Certificate of Completion".
            Kind.Contract, Kind.Certificate, Kind.Form -> heading ?: join(kind.label, who)
            else -> join(kind.label, who, date)
        }
        return name
    }

    /** The kind whose giveaway words the page has most of, if it has enough to tell. */
    internal fun kind(rows: List<Row>): Kind? {
        val text = rows.joinToString("\n") { it.text.lowercase() }
        val prices = rows.count { PRICE_AT_END.containsMatchIn(it.text) }
        val scores = Kind.entries.associateWith { kind ->
            var score = kind.strong.count { has(text, it) } * STRONG + kind.weak.count { has(text, it) } * WEAK
            // A column of prices is what a till roll or a bill looks like.
            if (prices >= MIN_PRICES && (kind == Kind.Receipt || kind == Kind.Invoice || kind == Kind.Quote)) score += PRICES
            if (kind == Kind.Letter && rows.any { it.text.lowercase().startsWith("dear ") }) score += GREETING
            score
        }
        val best = scores.maxBy { it.value }
        return best.key.takeIf { best.value >= MIN_SCORE }
    }

    /**
     * Who the paper is from: the most prominent line near the top that's a name, not an
     * address, a number, a date or the kind of paper itself ("TAX INVOICE").
     */
    internal fun issuer(rows: List<Row>, kind: Kind): String? {
        val candidates = rows
            .filter { it.top < ISSUER_ZONE }
            .filter { looksLikeName(it.text, kind) }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        // Letterheads and shop names are set larger than the address under them.
        val tallest = candidates.maxOf { it.height }
        val pick = candidates.first { it.height >= tallest * PROMINENT }
        return tidyName(pick.text)
    }

    private fun looksLikeName(text: String, kind: Kind): Boolean {
        val lower = text.lowercase()
        val wordCount = text.split(' ').size
        if (wordCount > MAX_NAME_WORDS) return false
        if (NOT_A_NAME.any { has(lower, it) }) return false
        if (CONTACT.containsMatchIn(lower)) return false
        if (anyDate(text) != null) return false
        val digits = text.count(Char::isDigit)
        val letters = text.count(Char::isLetter)
        if (digits > 0 && (ADDRESS.containsMatchIn(lower) || digits * 2 > letters)) return false
        // Without the words that only say what it is, is there a name left?
        val rest = lower.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() && it !in FILLER && Kind.entries.none { k -> it in k.strong || it in k.weak } }
        return rest.sumOf { it.length } >= MIN_NAME_LETTERS
    }

    /** A letter's "Re: …" or "Subject: …" line, without the label. */
    private fun subject(rows: List<Row>): String? = rows.firstNotNullOfOrNull { row ->
        SUBJECT.find(row.text)?.groupValues?.get(1)?.let { DocumentTitles.tidy(it.take(MAX_SUBJECT)) }
    }

    /** The paper's own date: the first one on it that isn't when something is due or expires. */
    internal fun date(rows: List<Row>): LocalDate? = rows
        .filterNot { row -> OTHER_DATES.any { has(row.text.lowercase(), it) } }
        .firstNotNullOfOrNull { anyDate(it.text) }

    internal fun anyDate(text: String): LocalDate? {
        ISO.find(text)?.let { m -> valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        DAY_MONTH_NAME.find(text)?.let { m -> month(m.groupValues[2])?.let { mo -> valid(year(m.groupValues[3]), mo, m.groupValues[1].toInt())?.let { return it } } }
        MONTH_NAME_DAY.find(text)?.let { m -> month(m.groupValues[1])?.let { mo -> valid(year(m.groupValues[3]), mo, m.groupValues[2].toInt())?.let { return it } } }
        NUMERIC.find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val y = year(m.groupValues[3])
            // Whichever can't be a month settles it; otherwise the phone's region does.
            val (month, day) = when {
                a > 12 -> b to a
                b > 12 -> a to b
                monthFirst -> a to b
                else -> b to a
            }
            valid(y, month, day)?.let { return it }
        }
        return null
    }

    private fun valid(year: Int, month: Int, day: Int): LocalDate? =
        if (year !in MIN_YEAR..MAX_YEAR) null else try { LocalDate.of(year, month, day) } catch (e: DateTimeException) { null }

    private fun year(text: String): Int = text.toInt().let { if (text.length == 2) 2000 + it else it }

    private fun month(text: String): Int? = MONTHS.indexOfFirst { text.lowercase().startsWith(it) }.takeIf { it >= 0 }?.plus(1)

    /** Whether the name is only the kind of paper ("INVOICE", "Receipt"), saying nothing more. */
    private fun bare(name: String, kind: Kind): Boolean {
        val rest = name.lowercase().split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() && it !in FILLER && it !in kind.strong && it !in kind.weak }
        return rest.isEmpty()
    }

    /** "TESCO STORES LTD" → "Tesco Stores": capitals softened, company suffixes and stray marks dropped. */
    internal fun tidyName(text: String): String? {
        var t = text.trim().trim('*', '-', '=', '_', '#', ':', '|', '.', ',').trim()
        t = SUFFIX.replace(t, "").trim().trimEnd(',', '.', '&').trim()
        val letters = t.filter(Char::isLetter)
        if (letters.length >= 4 && letters.count(Char::isUpperCase) * 10 >= letters.length * 9) {
            t = t.split(' ').joinToString(" ") { w -> if (w in KEEP_UPPER) w else w.lowercase().replaceFirstChar { it.titlecase() } }
        }
        if (t.length > MAX_NAME_LENGTH) t = t.take(MAX_NAME_LENGTH).substringBeforeLast(' ')
        return t.takeIf { it.count(Char::isLetter) >= MIN_NAME_LETTERS }
    }

    private fun join(vararg parts: String?) = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" – ")

    /** A line of the page: its words left to right, where it sits, and its letter height. */
    internal data class Row(val text: String, val top: Float, val height: Float)

    internal companion object {
        /** Words on a line overlap in height; a word joins a line when its middle is this close. */
        private const val SAME_LINE = 0.5f

        fun rows(words: List<PdfWord>): List<Row> {
            val sorted = words.filter { it.text.isNotBlank() }.sortedBy { (it.top + it.bottom) / 2 }
            val lines = mutableListOf<MutableList<PdfWord>>()
            for (w in sorted) {
                val mid = (w.top + w.bottom) / 2
                val line = lines.lastOrNull()?.takeIf { l ->
                    val lm = l.map { (it.top + it.bottom) / 2 }.average().toFloat()
                    val lh = l.map { it.bottom - it.top }.average().toFloat()
                    abs(mid - lm) < SAME_LINE * maxOf(lh, w.bottom - w.top)
                }
                if (line != null) line += w else lines += mutableListOf(w)
            }
            return lines.map { line ->
                val heights = line.map { it.bottom - it.top }.sorted()
                Row(line.sortedBy { it.left }.joinToString(" ") { it.text }, line.minOf { it.top }, heights[heights.size / 2])
            }
        }

        private fun has(text: String, phrase: String) =
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)

        private const val STRONG = 3
        private const val WEAK = 1
        private const val PRICES = 2
        /** A line that opens "Dear …" is a letter's greeting, not the word in passing. */
        private const val GREETING = 2
        private const val MIN_PRICES = 4
        private const val MIN_SCORE = 4
        private const val ISSUER_ZONE = 0.35f
        private const val MAX_CANDIDATES = 4
        private const val PROMINENT = 0.85f
        private const val MAX_NAME_WORDS = 6
        private const val MIN_NAME_LETTERS = 3
        private const val MAX_NAME_LENGTH = 32
        private const val MAX_SUBJECT = 60
        private const val MIN_YEAR = 1950
        private const val MAX_YEAR = 2100

        private val MONTH_FIRST_COUNTRIES = setOf("US", "PH", "FM", "MH", "PW", "BZ")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private const val MONTH_NAME = "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)"
        private val ISO = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
        private val NUMERIC = Regex("""(?<![\d.])(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4}|\d{2})(?![\d.])""")
        private val DAY_MONTH_NAME = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-]+$MONTH_NAME\.?,?[\s\-]+(\d{4}|\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
        private val MONTH_NAME_DAY = Regex("""$MONTH_NAME\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""", RegexOption.IGNORE_CASE)
        private val PRICE_AT_END = Regex("""\d[.,]\d{2}\s*[A-Z€$£]?$""")
        private val SUBJECT = Regex("""^\s*(?:re|subject|subj)\s*[:\-]\s*(.{3,})$""", RegexOption.IGNORE_CASE)
        private val CONTACT = Regex("""@|www\.|https?:|\.com\b|\.co\.|\.org\b|\.net\b|\b(?:tel|phone|fax|mob|mobile|email|e-mail)\b|\+\d""")
        private val ADDRESS = Regex("""\b(?:street|st|road|rd|avenue|ave|lane|ln|drive|dr|blvd|boulevard|way|close|court|ct|place|pl|suite|ste|floor|unit|po box|p\.o\.|square|sq|highway|hwy)\b""")
        private val SUFFIX = Regex("""[\s,]+(?:ltd\.?|limited|inc\.?|llc|l\.l\.c\.|plc|gmbh|pty\.?(?:\s+ltd\.?)?|corp\.?|co\.)$""", RegexOption.IGNORE_CASE)

        /** Lines that are never who it's from. */
        private val NOT_A_NAME = listOf(
            "dear", "page", "date", "invoice no", "invoice number", "receipt no", "account number", "account no",
            "vat no", "vat reg", "gst", "abn", "tax id", "ein", "reg no", "company no", "order no", "customer no",
            "bill to", "billed to", "ship to", "sold to", "deliver to", "to", "attn", "attention", "total", "subtotal",
            "thank you", "welcome", "copy", "duplicate",
        )

        /** Words that only say what the paper is, not whose. */
        private val FILLER = setOf("tax", "sales", "copy", "customer", "original", "official", "simplified", "the", "of", "a", "your", "and", "for", "no", "number", "summary", "e")
        private val KEEP_UPPER = setOf("UK", "US", "USA", "EU", "NHS", "HMRC", "IRS", "BT", "EE", "ATM", "DVLA", "HSBC", "RBS", "TSB", "UPS", "DHL", "BP", "KFC", "IKEA", "H&M", "M&S")

        /** Dates on lines with these are deadlines or birthdays, not the paper's own. */
        private val OTHER_DATES = listOf("due", "expiry", "expires", "exp", "valid until", "valid to", "birth", "dob", "pay by", "payment by", "until")
    }
}
