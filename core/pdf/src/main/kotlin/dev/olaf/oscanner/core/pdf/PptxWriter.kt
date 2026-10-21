package dev.olaf.oscanner.core.pdf

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One slide: the page as a JPEG, its size in pixels, and the text read from it. */
class PptxSlide(val jpeg: ByteArray, val pixelWidth: Int, val pixelHeight: Int, val text: String)

/**
 * A PowerPoint deck (.pptx, Office Open XML): each page fills a slide, and its text sits in a
 * box behind the picture, so the deck can be searched and the text copied out. The slide shape
 * follows the first page. Written by hand, like the other exports: a zip of XML parts, with the
 * master, layout and theme every deck needs kept as small as PowerPoint accepts.
 */
object PptxWriter {

    fun write(title: String, slides: List<PptxSlide>, out: OutputStream) {
        require(slides.isNotEmpty()) { "A deck needs at least one slide" }
        val first = slides.first()
        // Landscape pages on a 16:9-wide slide; upright ones on an A4-wide one. Height follows.
        val cx = if (first.pixelWidth >= first.pixelHeight) WIDE else UPRIGHT
        val cy = (cx * first.pixelHeight / first.pixelWidth.coerceAtLeast(1)).coerceIn(MIN_SIDE, MAX_SIDE)
        ZipOutputStream(out).use { zip ->
            fun part(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            fun part(name: String, xml: String) = part(name, xml.toByteArray(Charsets.UTF_8))
            part("[Content_Types].xml", contentTypes(slides.size))
            part("_rels/.rels", ROOT_RELS)
            part("docProps/core.xml", core(title))
            part("ppt/presentation.xml", presentation(slides.size, cx, cy))
            part("ppt/_rels/presentation.xml.rels", presentationRels(slides.size))
            part("ppt/slideMasters/slideMaster1.xml", MASTER)
            part("ppt/slideMasters/_rels/slideMaster1.xml.rels", MASTER_RELS)
            part("ppt/slideLayouts/slideLayout1.xml", LAYOUT)
            part("ppt/slideLayouts/_rels/slideLayout1.xml.rels", LAYOUT_RELS)
            part("ppt/theme/theme1.xml", THEME)
            slides.forEachIndexed { i, slide ->
                val n = i + 1
                part("ppt/slides/slide$n.xml", slide(slide, cx, cy))
                part("ppt/slides/_rels/slide$n.xml.rels", slideRels(n))
                part("ppt/media/page$n.jpeg", slide.jpeg)
            }
        }
    }

    private fun slide(slide: PptxSlide, cx: Long, cy: Long): String {
        // The page as big as fits, centred.
        val scale = minOf(cx.toDouble() / slide.pixelWidth.coerceAtLeast(1), cy.toDouble() / slide.pixelHeight.coerceAtLeast(1))
        val w = (slide.pixelWidth * scale).toLong()
        val h = (slide.pixelHeight * scale).toLong()
        val x = (cx - w) / 2
        val y = (cy - h) / 2
        val paragraphs = slide.text.lines().map(String::trim).filter(String::isNotEmpty).joinToString("") {
            """<a:p><a:r><a:rPr lang="en-US" sz="1000" dirty="0"/><a:t>${escape(it)}</a:t></a:r></a:p>"""
        }.ifEmpty { "<a:p><a:endParaRPr lang=\"en-US\"/></a:p>" }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:spTree>""" +
            """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>""" +
            // Text first, so the picture covers it: found by search, not seen.
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Page text"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr wrap="square" rtlCol="0"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>""" +
            """<p:pic><p:nvPicPr><p:cNvPr id="3" name="Page"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
            """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun slideRels(n: Int) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$RELS">""" +
            """<Relationship Id="rId1" Type="$REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>""" +
            """<Relationship Id="rId2" Type="$REL/image" Target="../media/page$n.jpeg"/></Relationships>"""

    private fun presentation(slides: Int, cx: Long, cy: Long) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" saveSubsetFonts="1">""")
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>""")
        for (n in 1..slides) append("""<p:sldId id="${255 + n}" r:id="rId${n + 2}"/>""")
        append("""</p:sldIdLst><p:sldSz cx="$cx" cy="$cy"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""")
    }

    private fun presentationRels(slides: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$RELS">""")
        append("""<Relationship Id="rId1" Type="$REL/slideMaster" Target="slideMasters/slideMaster1.xml"/>""")
        append("""<Relationship Id="rId2" Type="$REL/theme" Target="theme/theme1.xml"/>""")
        for (n in 1..slides) append("""<Relationship Id="rId${n + 2}" Type="$REL/slide" Target="slides/slide$n.xml"/>""")
        append("</Relationships>")
    }

    private fun contentTypes(slides: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/><Default Extension="jpeg" ContentType="image/jpeg"/>""")
        append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        append("""<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""")
        for (n in 1..slides) append("""<Override PartName="/ppt/slides/slide$n.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        append("</Types>")
    }

    private fun core(title: String) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
            """xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${escape(title)}</dc:title><dc:creator>Oscanner</dc:creator></cp:coreProperties>"""

    /** XML text: escapes markup and drops characters XML 1.0 can't hold. */
    private fun escape(text: String): String = buildString {
        for (c in text) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append(' ')
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }

    /** 13.333 in, the width of a 16:9 slide; 21 cm, an A4 page's. */
    private const val WIDE = 12_192_000L
    private const val UPRIGHT = 7_560_000L
    /** PowerPoint's limits on a slide's sides: 1 in to 56 in. */
    private const val MIN_SIDE = 914_400L
    private const val MAX_SIDE = 51_206_400L

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$RELS"><Relationship Id="rId1" Type="$REL/officeDocument" Target="ppt/presentation.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>"""

    private const val EMPTY_TREE = """<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree>"""

    private const val MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster xmlns:a="$A" xmlns:r="$R" xmlns:p="$P"><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg>$EMPTY_TREE</p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle><p:bodyStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:bodyStyle><p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"""

    private const val MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$RELS"><Relationship Id="rId1" Type="$REL/slideLayout" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId2" Type="$REL/theme" Target="../theme/theme1.xml"/></Relationships>"""

    private const val LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout xmlns:a="$A" xmlns:r="$R" xmlns:p="$P" type="blank" preserve="1"><p:cSld name="Blank">$EMPTY_TREE</p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"""

    private const val LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$RELS"><Relationship Id="rId1" Type="$REL/slideMaster" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""

    private const val SOLID = """<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>"""
    private const val LINE = """<a:ln w="9525">$SOLID</a:ln>"""
    private const val EFFECT = """<a:effectStyle><a:effectLst/></a:effectStyle>"""

    private const val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:theme xmlns:a="$A" name="Oscanner"><a:themeElements>""" +
        """<a:clrScheme name="Oscanner"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F1F1F"/></a:dk2><a:lt2><a:srgbClr val="EEEEEE"/></a:lt2>""" +
        """<a:accent1><a:srgbClr val="FF6B35"/></a:accent1><a:accent2><a:srgbClr val="4472C4"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>""" +
        """<a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
        """<a:fontScheme name="Oscanner"><a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>""" +
        """<a:fmtScheme name="Oscanner"><a:fillStyleLst>$SOLID$SOLID$SOLID</a:fillStyleLst><a:lnStyleLst>$LINE$LINE$LINE</a:lnStyleLst>""" +
        """<a:effectStyleLst>$EFFECT$EFFECT$EFFECT</a:effectStyleLst><a:bgFillStyleLst>$SOLID$SOLID$SOLID</a:bgFillStyleLst></a:fmtScheme>""" +
        """</a:themeElements></a:theme>"""
}
