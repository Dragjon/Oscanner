package dev.olaf.oscanner.core.pdf

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** A run of text set one way. [size] is in points; [color] is 0xRRGGBB. */
data class OfficeRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val size: Float? = null,
    val color: Int? = null,
)

enum class OfficeAlign { Start, Center, End, Justify }

/** Something on a page of a Word document, in order. */
sealed interface OfficeBlock

/** A paragraph: [heading] 1 for the biggest, 0 for body text; [marker] is its bullet or number. */
data class OfficeParagraph(
    val runs: List<OfficeRun>,
    val heading: Int = 0,
    val align: OfficeAlign = OfficeAlign.Start,
    val marker: String? = null,
    val level: Int = 0,
) : OfficeBlock {
    val text: String get() = runs.joinToString("") { it.text }
}

/** A table: each row's cells, each cell's paragraphs and how many grid columns it spans; [widths] in points. */
data class OfficeTable(val rows: List<List<OfficeCell>>, val widths: List<Float>) : OfficeBlock

data class OfficeCell(val paragraphs: List<OfficeParagraph>, val span: Int = 1)

/** A picture, [width] by [height] points. */
class OfficePicture(val bytes: ByteArray, val width: Float, val height: Float) : OfficeBlock

data object OfficePageBreak : OfficeBlock

/** What an Office file holds, read for laying out as pages. */
sealed interface OfficeFile

/** A Word document. Sizes in points. */
data class WordDocument(
    val blocks: List<OfficeBlock>,
    val pageWidth: Float = 595f,
    val pageHeight: Float = 842f,
    val margins: Margins = Margins(72f, 72f, 72f, 72f),
    val textSize: Float = 11f,
) : OfficeFile

data class Margins(val top: Float, val right: Float, val bottom: Float, val left: Float)

/** A PowerPoint deck, [width] by [height] points a slide. */
data class Presentation(val width: Float, val height: Float, val slides: List<Slide>) : OfficeFile

/** A slide: its [background] colour (0xRRGGBB) and what's on it, back to front. */
data class Slide(val shapes: List<SlideShape>, val background: Int? = null)

/** Something on a slide, its box in points from the slide's top left. */
sealed interface SlideShape {
    val x: Float
    val y: Float
    val width: Float
    val height: Float
}

/** Text in a box: [fill] its colour (0xRRGGBB) or null for none; [anchor] -1 top, 0 middle, 1 bottom. */
data class SlideText(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val paragraphs: List<OfficeParagraph>,
    val fill: Int? = null,
    val anchor: Int = -1,
) : SlideShape

class SlidePicture(override val x: Float, override val y: Float, override val width: Float, override val height: Float, val bytes: ByteArray) : SlideShape

data class SlideTable(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val rows: List<List<String>>,
) : SlideShape

/** An Excel workbook. */
data class Workbook(val sheets: List<Sheet>) : OfficeFile

/** A sheet's cells as shown, row by row; [widths] are its columns' widths in characters. */
data class Sheet(val name: String, val rows: List<List<String>>, val widths: List<Float>)

/**
 * Reads Word (.docx), PowerPoint (.pptx) and Excel (.xlsx) files: zips of XML, read straight,
 * for what a page needs to show (text and how it's set, tables, pictures, where things sit on a
 * slide), not everything Office can do. The older binary formats (.doc, .ppt, .xls) aren't read.
 */
object OfficeReader {

    /** Reads the file in [input]; throws when it isn't a Word, PowerPoint or Excel file, or is damaged. */
    fun read(input: InputStream): OfficeFile {
        val zip = unzip(input)
        return when {
            "word/document.xml" in zip -> Word(zip).read()
            "ppt/presentation.xml" in zip -> Deck(zip).read()
            "xl/workbook.xml" in zip -> Book(zip).read()
            else -> error("Not a Word, PowerPoint or Excel file")
        }
    }

    /** Whether a file named [name] or of type [mime] is one [read] takes. */
    fun handles(name: String?, mime: String?): Boolean =
        mime in MIME_TYPES || name?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS

    val MIME_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    )
    private val EXTENSIONS = setOf("docx", "pptx", "xlsx")

    /** Every entry, each at most [MAX_ENTRY] bytes and all together [MAX_TOTAL]: a zip can unpack to far more than it is. */
    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    bytes.write(buffer, 0, n)
                    total += n
                    require(bytes.size() <= MAX_ENTRY && total <= MAX_TOTAL) { "The file unpacks to more than can be read" }
                }
                out[entry.name.removePrefix("/")] = bytes.toByteArray()
            }
        }
        require(out.isNotEmpty()) { "Not a zip file" }
        return out
    }

    private fun xml(bytes: ByteArray): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        // Office files never need a DTD or outside entities; refuse them where the parser can.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.isExpandEntityReferences = false }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    }

    // Elements by local name, ignoring namespaces: Office's prefixes vary between writers.
    private fun Element.kids(name: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && (name == null || n.localName == name)) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.kid(name: String): Element? {
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.localName == name) return n
            n = n.nextSibling
        }
        return null
    }

    private fun Element.path(vararg names: String): Element? {
        var e: Element? = this
        for (name in names) e = e?.kid(name)
        return e
    }

    /** All descendants named [name], in document order. */
    private fun Element.all(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (k in e.kids()) {
                if (k.localName == name) out += k
                walk(k)
            }
        }
        walk(this)
        return out
    }

    /** An attribute by local name, whatever its prefix. */
    private fun Element.at(name: String): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val a = attrs.item(i)
            if ((a.localName ?: a.nodeName.substringAfter(':')) == name) return a.nodeValue
        }
        return null
    }

    /** A relationship id (r:id), told from a plain id="…" beside it by its namespace. */
    private fun Element.relId(): String? = getAttributeNS(RELATIONSHIPS, "id").takeIf { it.isNotEmpty() }

    /** A Word on/off property: present and not "0"/"false". */
    private fun Element?.on(): Boolean = this != null && at("val").let { it == null || (it != "0" && it != "false" && it != "none") }

    private fun color(hex: String?): Int? = hex?.takeIf { it.length == 6 }?.toIntOrNull(16)

    /** Relationship ids to the parts they point at, for the part at [part]. */
    private fun rels(zip: Map<String, ByteArray>, part: String): Map<String, String> {
        val dir = part.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + part.substringAfterLast('/') + ".rels"
        val bytes = zip[relsPath] ?: return emptyMap()
        return xml(bytes).kids("Relationship").associate { r -> (r.at("Id") ?: "") to resolve(dir, r.at("Target") ?: "") }
    }

    /** [target] relative to the folder [dir], as a path in the zip. */
    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = ArrayList(if (dir.isEmpty()) emptyList() else dir.split('/'))
        for (p in target.split('/')) {
            when (p) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> Unit
                else -> parts += p
            }
        }
        return parts.joinToString("/")
    }

    // ---- Word ----

    private class Word(val zip: Map<String, ByteArray>) {
        val rels = rels(zip, "word/document.xml")
        /** Style id to its heading level (1 for Title and Heading 1), from its name. */
        val headings = HashMap<String, Int>()
        /** Style id to the style it's based on. */
        val basedOn = HashMap<String, String>()
        /** Style id to the list it puts its paragraphs in, and at which level: List Bullet, List Number. */
        val styleLists = HashMap<String, Pair<String, Int>>()
        var textSize = 11f
        val numbering = Numbering(zip["word/numbering.xml"]?.let(::xml))

        fun read(): WordDocument {
            zip["word/styles.xml"]?.let(::xml)?.let { styles ->
                styles.path("docDefaults", "rPrDefault", "rPr", "sz")?.at("val")?.toFloatOrNull()?.let { textSize = it / 2 }
                for (s in styles.kids("style")) {
                    val id = s.at("styleId") ?: continue
                    val name = s.kid("name")?.at("val")?.lowercase() ?: continue
                    s.kid("basedOn")?.at("val")?.let { basedOn[id] = it }
                    s.path("pPr", "numPr")?.let { n ->
                        n.kid("numId")?.at("val")?.let { num -> styleLists[id] = num to (n.kid("ilvl")?.at("val")?.toIntOrNull() ?: 0) }
                    }
                    when {
                        name == "title" -> headings[id] = 1
                        name.startsWith("heading") -> name.removePrefix("heading").trim().toIntOrNull()?.let { headings[id] = it.coerceIn(1, 6) }
                    }
                }
            }
            val root = xml(zip.getValue("word/document.xml"))
            val body = root.kid("body") ?: error("A Word document without a body")
            val blocks = ArrayList<OfficeBlock>()
            body.kids().forEach { read(it, blocks) }
            var doc = WordDocument(blocks, textSize = textSize)
            body.kid("sectPr")?.let { sect ->
                sect.kid("pgSz")?.let { sz ->
                    val w = sz.at("w")?.toFloatOrNull()?.div(20)
                    val h = sz.at("h")?.toFloatOrNull()?.div(20)
                    if (w != null && h != null && w > 50 && h > 50) doc = doc.copy(pageWidth = w, pageHeight = h)
                }
                sect.kid("pgMar")?.let { m ->
                    fun twips(n: String, fallback: Float) = m.at(n)?.toFloatOrNull()?.div(20)?.coerceIn(0f, 200f) ?: fallback
                    doc = doc.copy(margins = Margins(twips("top", 72f), twips("right", 72f), twips("bottom", 72f), twips("left", 72f)))
                }
            }
            return doc
        }

        private fun read(e: Element, out: MutableList<OfficeBlock>) {
            when (e.localName) {
                "p" -> paragraph(e, out)
                "tbl" -> out += table(e)
                // Content controls and inserted text hold paragraphs of their own.
                "sdt" -> e.kid("sdtContent")?.kids()?.forEach { read(it, out) }
                "ins", "customXml", "smartTag" -> e.kids().forEach { read(it, out) }
            }
        }

        private fun heading(styleId: String?): Int {
            var id = styleId
            repeat(8) {
                val s = id ?: return 0
                headings[s]?.let { return it }
                id = basedOn[s]
            }
            return 0
        }

        /** The list a paragraph's style puts it in, following the styles it's based on. */
        private fun styleList(styleId: String?): Pair<String, Int>? {
            var id = styleId
            repeat(8) {
                val s = id ?: return null
                styleLists[s]?.let { return it }
                id = basedOn[s]
            }
            return null
        }

        private fun paragraph(p: Element, out: MutableList<OfficeBlock>) {
            val pPr = p.kid("pPr")
            if (pPr?.kid("pageBreakBefore").on()) out += OfficePageBreak
            val styleId = pPr?.kid("pStyle")?.at("val")
            val heading = heading(styleId)
            val align = when (pPr?.kid("jc")?.at("val")) {
                "center" -> OfficeAlign.Center
                "right", "end" -> OfficeAlign.End
                "both", "distribute" -> OfficeAlign.Justify
                else -> OfficeAlign.Start
            }
            // In a list by its own say, or by its style's.
            val numPr = pPr?.kid("numPr")
            val fromStyle = styleList(styleId)
            val numId = numPr?.kid("numId")?.at("val") ?: fromStyle?.first
            val level = numPr?.kid("ilvl")?.at("val")?.toIntOrNull() ?: fromStyle?.second ?: 0
            val marker = numId?.let { numbering.next(it, level) }
            val runs = ArrayList<OfficeRun>()
            val pictures = ArrayList<OfficePicture>()
            var breakAfter = false
            fun flush() {
                if (runs.isNotEmpty() || pictures.isEmpty()) out += OfficeParagraph(runs.toList(), heading, align, marker, level)
                runs.clear()
            }
            fun runsOf(parent: Element) {
                for (k in parent.kids()) {
                    when (k.localName) {
                        "r" -> {
                            val rPr = k.kid("rPr")
                            val style = OfficeRun(
                                "",
                                bold = rPr?.kid("b").on(),
                                italic = rPr?.kid("i").on(),
                                underline = rPr?.kid("u").on(),
                                size = rPr?.kid("sz")?.at("val")?.toFloatOrNull()?.div(2),
                                color = color(rPr?.kid("color")?.at("val")),
                            )
                            for (part in k.kids()) {
                                when (part.localName) {
                                    "t" -> runs += style.copy(text = part.textContent)
                                    "tab" -> runs += style.copy(text = "\t")
                                    "br", "cr" -> if (part.at("type") == "page") breakAfter = true else runs += style.copy(text = "\n")
                                    "drawing", "pict", "object" -> pictures += picturesIn(part)
                                    "sym" -> part.at("char")?.toIntOrNull(16)?.let { runs += style.copy(text = String(Character.toChars(it))) }
                                }
                            }
                        }
                        "hyperlink", "fldSimple", "ins", "smartTag", "customXml" -> runsOf(k)
                        "sdt" -> k.kid("sdtContent")?.let(::runsOf)
                    }
                }
            }
            runsOf(p)
            // Pictures in a paragraph stand before its text: Word sets most inline, on a line of their own.
            out += pictures
            flush()
            if (breakAfter) out += OfficePageBreak
        }

        private fun picturesIn(e: Element): List<OfficePicture> {
            val out = ArrayList<OfficePicture>()
            for (blip in e.all("blip") + e.all("imagedata")) {
                val id = blip.at("embed") ?: blip.at("id") ?: continue
                val bytes = rels[id]?.let { zip[it] } ?: continue
                // The size Word shows it at: its extent, in EMUs (914400 an inch).
                val extent = e.all("extent").firstOrNull()
                val w = extent?.at("cx")?.toFloatOrNull()?.div(EMU_PER_POINT) ?: 0f
                val h = extent?.at("cy")?.toFloatOrNull()?.div(EMU_PER_POINT) ?: 0f
                out += OfficePicture(bytes, w, h)
            }
            return out
        }

        private fun table(t: Element): OfficeTable {
            val widths = t.kid("tblGrid")?.kids("gridCol")?.map { (it.at("w")?.toFloatOrNull() ?: 0f) / 20 }.orEmpty()
            val rows = t.kids("tr").map { tr ->
                tr.kids("tc").map { tc ->
                    val span = tc.path("tcPr", "gridSpan")?.at("val")?.toIntOrNull() ?: 1
                    val blocks = ArrayList<OfficeBlock>()
                    tc.kids().forEach { read(it, blocks) }
                    // A table inside a cell comes out as its paragraphs.
                    val paragraphs = blocks.flatMap { b ->
                        when (b) {
                            is OfficeParagraph -> listOf(b)
                            is OfficeTable -> b.rows.flatMap { r -> r.flatMap { it.paragraphs } }
                            else -> emptyList()
                        }
                    }
                    OfficeCell(paragraphs, span.coerceAtLeast(1))
                }
            }
            return OfficeTable(rows, widths)
        }
    }

    /** Word's lists: each list's levels, their kind and their count so far. */
    private class Numbering(root: Element?) {
        private val formats = HashMap<String, Map<Int, Pair<String, String>>>()
        private val counters = HashMap<String, IntArray>()

        init {
            if (root != null) {
                val abstract = root.kids("abstractNum").associate { a ->
                    (a.at("abstractNumId") ?: "") to a.kids("lvl").associate { l ->
                        (l.at("ilvl")?.toIntOrNull() ?: 0) to ((l.kid("numFmt")?.at("val") ?: "bullet") to (l.kid("lvlText")?.at("val") ?: "%1."))
                    }
                }
                for (n in root.kids("num")) {
                    val id = n.at("numId") ?: continue
                    abstract[n.kid("abstractNumId")?.at("val")]?.let { formats[id] = it }
                }
            }
        }

        /** The marker of the next item at [level] of list [numId]. */
        fun next(numId: String, level: Int): String? {
            if (numId == "0") return null
            val levels = formats[numId] ?: return "•"
            val (format, text) = levels[level] ?: return "•"
            val count = counters.getOrPut(numId) { IntArray(9) }
            val l = level.coerceIn(0, 8)
            count[l]++
            for (deeper in l + 1 until 9) count[deeper] = 0
            if (format == "bullet" || format == "none") return if (format == "none") null else "•"
            var out = text
            for (k in 0..l) {
                val f = levels[k]?.first ?: "decimal"
                out = out.replace("%${k + 1}", number(count[k].coerceAtLeast(1), f))
            }
            return out
        }

        private fun number(n: Int, format: String): String = when (format) {
            "lowerLetter" -> letters(n).lowercase()
            "upperLetter" -> letters(n)
            "lowerRoman" -> roman(n).lowercase()
            "upperRoman" -> roman(n)
            else -> n.toString()
        }

        private fun letters(n: Int): String {
            var k = n
            val sb = StringBuilder()
            while (k > 0) {
                k--
                sb.insert(0, 'A' + k % 26)
                k /= 26
            }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val marks = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var k = n.coerceIn(1, 3999)
            val sb = StringBuilder()
            for (i in values.indices) while (k >= values[i]) {
                sb.append(marks[i])
                k -= values[i]
            }
            return sb.toString()
        }
    }

    // ---- PowerPoint ----

    private class Deck(val zip: Map<String, ByteArray>) {
        fun read(): Presentation {
            val pres = xml(zip.getValue("ppt/presentation.xml"))
            val size = pres.kid("sldSz")
            val width = (size?.at("cx")?.toFloatOrNull() ?: 9_144_000f) / EMU_PER_POINT
            val height = (size?.at("cy")?.toFloatOrNull() ?: 6_858_000f) / EMU_PER_POINT
            val rels = rels(zip, "ppt/presentation.xml")
            val slides = pres.kid("sldIdLst")?.kids("sldId").orEmpty().mapNotNull { id ->
                val path = rels[id.relId()] ?: return@mapNotNull null
                zip[path]?.let { slide(path, it) }
            }.take(MAX_SLIDES)
            return Presentation(width, height, slides)
        }

        private class Box(val x: Float, val y: Float, val w: Float, val h: Float)

        /** A layout's or master's placeholders, by index and by type. */
        private class Placeholders(val byIdx: Map<String, Element>, val byType: Map<String, Element>) {
            fun find(idx: String?, type: String): Element? =
                idx?.let { byIdx[it] } ?: byType[type] ?: when (type) {
                    "ctrTitle" -> byType["title"]
                    "subTitle", "obj" -> byType["body"]
                    else -> null
                }
        }

        private fun placeholders(root: Element?): Placeholders {
            if (root == null) return Placeholders(emptyMap(), emptyMap())
            val byIdx = HashMap<String, Element>()
            val byType = HashMap<String, Element>()
            for (sp in root.all("sp")) {
                val ph = sp.path("nvSpPr", "nvPr", "ph") ?: continue
                ph.at("idx")?.let { byIdx[it] = sp }
                byType.putIfAbsent(ph.at("type") ?: "body", sp)
            }
            return Placeholders(byIdx, byType)
        }

        private fun box(xfrm: Element?): Box? {
            val off = xfrm?.kid("off") ?: return null
            val ext = xfrm.kid("ext") ?: return null
            return Box(
                (off.at("x")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
                (off.at("y")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
                (ext.at("cx")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
                (ext.at("cy")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
            )
        }

        /**
         * How a slide's text is set where it doesn't say: its placeholder on the layout, then on
         * the master, then the master's title or body style, each level of a list by itself.
         */
        private class Inherited(val lists: List<Element>, val bodies: List<Element>) {
            private fun level(n: Int) = lists.mapNotNull { it.kid("lvl${(n + 1).coerceIn(1, 9)}pPr") }
            fun align(n: Int): String? = level(n).firstNotNullOfOrNull { it.at("algn") }
            fun size(n: Int): Float? = level(n).firstNotNullOfOrNull { it.kid("defRPr")?.at("sz")?.toFloatOrNull()?.div(100) }
            fun bold(n: Int): Boolean? = level(n).firstNotNullOfOrNull { l -> l.kid("defRPr")?.at("b")?.let { it == "1" } }
            /** The bullet: "" for none said outright, null when nothing says. */
            fun bullet(n: Int): String? = level(n).firstNotNullOfOrNull { l -> if (l.kid("buNone") != null) "" else l.kid("buChar")?.at("char") }
            fun anchor(): String? = bodies.firstNotNullOfOrNull { it.at("anchor") }
        }

        private fun slide(path: String, bytes: ByteArray): Slide {
            val rels = rels(zip, path)
            val layoutPath = rels.values.firstOrNull { it.contains("slideLayouts/") }
            val masterPath = layoutPath?.let { rels(zip, it).values.firstOrNull { p -> p.contains("slideMasters/") } }
            val layoutRoot = layoutPath?.let { zip[it] }?.let(::xml)
            val masterRoot = masterPath?.let { zip[it] }?.let(::xml)
            val layout = placeholders(layoutRoot)
            val master = placeholders(masterRoot)
            val styles = masterRoot?.kid("txStyles")
            fun inherited(own: Element?, ph: Element?): Inherited {
                val type = ph?.let { it.at("type") ?: "body" }
                val idx = ph?.at("idx")
                val shapes = listOfNotNull(own, type?.let { layout.find(idx, it) }, type?.let { master.find(idx, it) })
                val txStyle = when (type) {
                    "title", "ctrTitle" -> styles?.kid("titleStyle")
                    "body", "obj", "subTitle" -> styles?.kid("bodyStyle")
                    else -> styles?.kid("otherStyle")
                }
                return Inherited(
                    shapes.mapNotNull { it.path("txBody", "lstStyle") } + listOfNotNull(txStyle),
                    shapes.mapNotNull { it.path("txBody", "bodyPr") },
                )
            }
            fun placed(ph: Element): Box? {
                val type = ph.at("type") ?: "body"
                val idx = ph.at("idx")
                return box(layout.find(idx, type)?.path("spPr", "xfrm")) ?: box(master.find(idx, type)?.path("spPr", "xfrm"))
            }
            val root = xml(bytes)
            val shapes = ArrayList<SlideShape>()
            root.path("cSld", "spTree")?.let { tree -> shapes(tree, rels, ::placed, ::inherited, Transform.NONE, shapes) }
            fun backgroundOf(r: Element?) = color(r?.path("cSld", "bg", "bgPr", "solidFill", "srgbClr")?.at("val"))
            return Slide(shapes, backgroundOf(root) ?: backgroundOf(layoutRoot) ?: backgroundOf(masterRoot))
        }

        /** Child coordinates of a group to the slide's: x' = x0 + (x − cx) · sx. */
        private class Transform(val x0: Float, val y0: Float, val cx: Float, val cy: Float, val sx: Float, val sy: Float) {
            fun x(v: Float) = x0 + (v - cx) * sx
            fun y(v: Float) = y0 + (v - cy) * sy
            companion object {
                val NONE = Transform(0f, 0f, 0f, 0f, 1f, 1f)
            }
        }

        private fun shapes(
            tree: Element,
            rels: Map<String, String>,
            placed: (Element) -> Box?,
            inherited: (Element?, Element?) -> Inherited,
            t: Transform,
            out: MutableList<SlideShape>,
        ) {
            for (e in tree.kids()) {
                when (e.localName) {
                    "sp" -> {
                        val ph = e.path("nvSpPr", "nvPr", "ph")
                        val b = box(e.path("spPr", "xfrm")) ?: ph?.let(placed) ?: continue
                        val body = e.kid("txBody")
                        val type = ph?.at("type")
                        val title = type == "title" || type == "ctrTitle"
                        val style = inherited(e, ph)
                        val fallback = if (title) TITLE_SIZE else if (ph != null) BODY_SIZE else TEXT_SIZE
                        val paragraphs = body?.kids("p")?.map { paragraph(it, style, fallback, title) }.orEmpty()
                        val fill = color(e.path("spPr", "solidFill", "srgbClr")?.at("val"))
                        if (paragraphs.none { it.text.isNotBlank() } && fill == null) continue
                        val anchor = when (style.anchor()) {
                            "ctr" -> 0
                            "b" -> 1
                            else -> -1
                        }
                        out += SlideText(t.x(b.x), t.y(b.y), b.w * t.sx, b.h * t.sy, paragraphs, fill, anchor)
                    }
                    "pic" -> {
                        val b = box(e.path("spPr", "xfrm")) ?: continue
                        val id = e.all("blip").firstOrNull()?.at("embed") ?: continue
                        val bytes = rels[id]?.let { zip[it] } ?: continue
                        out += SlidePicture(t.x(b.x), t.y(b.y), b.w * t.sx, b.h * t.sy, bytes)
                    }
                    "graphicFrame" -> {
                        val b = box(e.kid("xfrm")) ?: continue
                        val tbl = e.all("tbl").firstOrNull() ?: continue
                        val rows = tbl.kids("tr").map { tr -> tr.kids("tc").map { tc -> tc.all("t").joinToString("") { it.textContent } } }
                        out += SlideTable(t.x(b.x), t.y(b.y), b.w * t.sx, b.h * t.sy, rows)
                    }
                    "grpSp" -> {
                        val x = e.path("grpSpPr", "xfrm")
                        val b = box(x)
                        val chOff = x?.kid("chOff")
                        val chExt = x?.kid("chExt")
                        val inner = if (b != null && chOff != null && chExt != null) {
                            val cw = (chExt.at("cx")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT
                            val ch = (chExt.at("cy")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT
                            val sx = if (cw > 0) b.w / cw else 1f
                            val sy = if (ch > 0) b.h / ch else 1f
                            Transform(
                                t.x(b.x), t.y(b.y),
                                (chOff.at("x")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
                                (chOff.at("y")?.toFloatOrNull() ?: 0f) / EMU_PER_POINT,
                                sx * t.sx, sy * t.sy,
                            )
                        } else {
                            t
                        }
                        shapes(e, rels, placed, inherited, inner, out)
                    }
                }
            }
        }

        private fun paragraph(p: Element, style: Inherited, fallback: Float, title: Boolean): OfficeParagraph {
            val pPr = p.kid("pPr")
            val level = pPr?.at("lvl")?.toIntOrNull() ?: 0
            val align = when (pPr?.at("algn") ?: style.align(level)) {
                "ctr" -> OfficeAlign.Center
                "r" -> OfficeAlign.End
                "just", "dist" -> OfficeAlign.Justify
                else -> OfficeAlign.Start
            }
            val size = style.size(level) ?: fallback
            val bold = style.bold(level) ?: false
            val runs = ArrayList<OfficeRun>()
            for (k in p.kids()) {
                when (k.localName) {
                    "r", "fld" -> {
                        val rPr = k.kid("rPr")
                        runs += OfficeRun(
                            k.kid("t")?.textContent.orEmpty(),
                            bold = rPr?.at("b")?.let { it == "1" } ?: bold,
                            italic = rPr?.at("i") == "1",
                            underline = rPr?.at("u").let { it != null && it != "none" },
                            size = rPr?.at("sz")?.toFloatOrNull()?.div(100) ?: size,
                            color = color(rPr?.path("solidFill", "srgbClr")?.at("val")),
                        )
                    }
                    "br" -> runs += OfficeRun("\n", size = size)
                }
            }
            // Its own bullet, or none, beats what it would inherit; an empty paragraph has none.
            val own = if (pPr?.kid("buNone") != null) "" else pPr?.kid("buChar")?.at("char")
            val bullet = (own ?: style.bullet(level))?.takeIf { it.isNotEmpty() && runs.any { r -> r.text.isNotBlank() } }
            return OfficeParagraph(runs, if (title) 1 else 0, align, bullet, level)
        }
    }

    // ---- Excel ----

    private class Book(val zip: Map<String, ByteArray>) {
        val strings: List<String> = zip["xl/sharedStrings.xml"]?.let(::xml)?.kids("si")?.map { si -> si.all("t").joinToString("") { it.textContent } }.orEmpty()
        /** Each cell style's number format: whether it shows a date, or a percentage. */
        val dates = ArrayList<Boolean>()
        val percents = ArrayList<Boolean>()

        fun read(): Workbook {
            zip["xl/styles.xml"]?.let(::xml)?.let { styles ->
                val custom = styles.kid("numFmts")?.kids("numFmt")?.associate { (it.at("numFmtId")?.toIntOrNull() ?: -1) to (it.at("formatCode") ?: "") }.orEmpty()
                for (xf in styles.kid("cellXfs")?.kids("xf").orEmpty()) {
                    val id = xf.at("numFmtId")?.toIntOrNull() ?: 0
                    val code = custom[id]?.lowercase()?.replace(Regex("\"[^\"]*\"|\\[[^]]*]"), "")
                    dates += id in 14..22 || id in 45..47 || (code != null && (code.contains('d') || code.contains('y') || (code.contains('m') && !code.contains('0'))))
                    percents += id == 9 || id == 10 || (code != null && code.contains('%'))
                }
            }
            val book = xml(zip.getValue("xl/workbook.xml"))
            val rels = rels(zip, "xl/workbook.xml")
            val sheets = book.kid("sheets")?.kids("sheet").orEmpty().mapNotNull { s ->
                if (s.at("state") == "hidden" || s.at("state") == "veryHidden") return@mapNotNull null
                val path = rels[s.relId()] ?: return@mapNotNull null
                zip[path]?.let { sheet(s.at("name") ?: "Sheet", it) }
            }
            return Workbook(sheets)
        }

        private fun sheet(name: String, bytes: ByteArray): Sheet {
            val root = xml(bytes)
            val cells = HashMap<Int, HashMap<Int, String>>()
            var lastRow = -1
            var lastCol = -1
            for (row in root.kid("sheetData")?.kids("row").orEmpty()) {
                val r = (row.at("r")?.toIntOrNull() ?: (lastRow + 2)) - 1
                if (r >= MAX_ROWS) break
                var nextCol = 0
                for (c in row.kids("c")) {
                    val col = c.at("r")?.let(::column) ?: nextCol
                    nextCol = col + 1
                    if (col >= MAX_COLS) continue
                    val text = value(c) ?: continue
                    if (text.isEmpty()) continue
                    cells.getOrPut(r) { HashMap() }[col] = text
                    lastRow = maxOf(lastRow, r)
                    lastCol = maxOf(lastCol, col)
                }
            }
            val rows = List(lastRow + 1) { r -> List(lastCol + 1) { c -> cells[r]?.get(c).orEmpty() } }
            val widths = MutableList(lastCol + 1) { DEFAULT_WIDTH }
            for (col in root.kid("cols")?.kids("col").orEmpty()) {
                val w = col.at("width")?.toFloatOrNull() ?: continue
                val hidden = col.at("hidden") == "1"
                val from = (col.at("min")?.toIntOrNull() ?: continue) - 1
                val to = (col.at("max")?.toIntOrNull() ?: continue) - 1
                for (i in from..minOf(to, lastCol)) if (i >= 0) widths[i] = if (hidden) 0f else w
            }
            return Sheet(name, rows, widths)
        }

        /** "BC12" → 54, the column's index from 0. */
        private fun column(ref: String): Int {
            var n = 0
            for (ch in ref) {
                if (!ch.isLetter()) break
                n = n * 26 + (ch.uppercaseChar() - 'A' + 1)
            }
            return n - 1
        }

        private fun value(c: Element): String? {
            val v = c.kid("v")?.textContent
            return when (c.at("t")) {
                "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }
                "inlineStr" -> c.kid("is")?.all("t")?.joinToString("") { it.textContent }
                "str", "e" -> v
                "b" -> if (v == "1") "TRUE" else "FALSE"
                else -> v?.let { number(it, c.at("s")?.toIntOrNull() ?: 0) }
            }
        }

        private fun number(v: String, style: Int): String {
            val d = v.toDoubleOrNull() ?: return v
            if (dates.getOrNull(style) == true && d > 0 && d < 2_958_466) {
                // Excel counts days from 30 December 1899 (its 1900 leap day mistake included).
                val date = LocalDate.of(1899, 12, 30).plusDays(d.toLong())
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            }
            if (percents.getOrNull(style) == true) return plain(BigDecimal(v).multiply(BigDecimal(100))) + "%"
            return plain(BigDecimal(v))
        }

        /** As few digits as show it: 3, 2.5, 0.125; long fractions cut to 10 places. */
        private fun plain(b: BigDecimal): String {
            val rounded = if (b.scale() > 10) b.setScale(10, java.math.RoundingMode.HALF_UP) else b
            return rounded.stripTrailingZeros().toPlainString()
        }
    }

    private const val RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val EMU_PER_POINT = 12_700f
    private const val TITLE_SIZE = 36f
    private const val BODY_SIZE = 24f
    private const val TEXT_SIZE = 18f
    private const val DEFAULT_WIDTH = 8.43f
    private const val MAX_SLIDES = 500
    private const val MAX_ROWS = 5_000
    private const val MAX_COLS = 200
    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024
}
