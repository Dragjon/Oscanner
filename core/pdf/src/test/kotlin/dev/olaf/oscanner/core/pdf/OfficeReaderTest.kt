package dev.olaf.oscanner.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeReaderTest {

    private fun zip(vararg entries: Pair<String, String>, binary: Map<String, ByteArray> = emptyMap()): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
            for ((name, bytes) in binary) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val w = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
        "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""

    @Test
    fun aWordDocumentsParagraphsTablesAndPictures() {
        val doc = """<w:document $w><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Minutes</w:t></w:r></w:p>
            <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="28"/><w:color w:val="C00000"/></w:rPr><w:t xml:space="preserve">Agreed </w:t></w:r><w:r><w:rPr><w:i/></w:rPr><w:t>today</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>First</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Second</w:t></w:r></w:p>
            <w:tbl><w:tblGrid><w:gridCol w:w="2880"/><w:gridCol w:w="1440"/></w:tblGrid>
              <w:tr><w:tc><w:p><w:r><w:t>Item</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Cost</w:t></w:r></w:p></w:tc></w:tr>
              <w:tr><w:tc><w:tcPr><w:gridSpan w:val="2"/></w:tcPr><w:p><w:r><w:t>Total</w:t></w:r></w:p></w:tc></w:tr>
            </w:tbl>
            <w:p><w:r><w:drawing><wp:inline><wp:extent cx="914400" cy="457200"/><a:graphic><a:graphicData><a:blip r:embed="rId5"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
            <w:p><w:r><w:t>Before</w:t><w:br w:type="page"/></w:r></w:p>
            <w:p><w:hyperlink><w:r><w:t>linked</w:t></w:r></w:hyperlink></w:p>
            <w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1080" w:bottom="1440" w:left="1080"/></w:sectPr>
            </w:body></w:document>"""
        val styles = """<w:styles $w><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults>
            <w:style w:styleId="Heading1"><w:name w:val="heading 1"/></w:style></w:styles>"""
        val numbering = """<w:numbering $w><w:abstractNum w:abstractNumId="7"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1)"/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="7"/></w:num></w:numbering>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId5" Target="media/image1.png"/></Relationships>"""
        val file = OfficeReader.read(
            zip(
                "word/document.xml" to doc, "word/styles.xml" to styles, "word/numbering.xml" to numbering, "word/_rels/document.xml.rels" to rels,
                binary = mapOf("word/media/image1.png" to byteArrayOf(1, 2, 3)),
            ),
        ) as WordDocument
        assertEquals(612f, file.pageWidth)
        assertEquals(792f, file.pageHeight)
        assertEquals(Margins(72f, 54f, 72f, 54f), file.margins)
        assertEquals(12f, file.textSize)
        val blocks = file.blocks
        val heading = blocks[0] as OfficeParagraph
        assertEquals("Minutes" to 1, heading.text to heading.heading)
        val agreed = blocks[1] as OfficeParagraph
        assertEquals(OfficeAlign.Center, agreed.align)
        assertEquals(OfficeRun("Agreed ", bold = true, size = 14f, color = 0xC00000), agreed.runs[0])
        assertTrue(agreed.runs[1].italic)
        assertEquals(listOf("1)", "2)"), listOf(blocks[2], blocks[3]).map { (it as OfficeParagraph).marker })
        val table = blocks[4] as OfficeTable
        assertEquals(listOf(144f, 72f), table.widths)
        assertEquals("Cost", table.rows[0][1].paragraphs[0].text)
        assertEquals(2, table.rows[1][0].span)
        val picture = blocks[5] as OfficePicture
        assertEquals(72f to 36f, picture.width to picture.height)
        assertEquals(3, picture.bytes.size)
        assertEquals("Before", (blocks[6] as OfficeParagraph).text)
        assertEquals(OfficePageBreak, blocks[7])
        assertEquals("linked", (blocks[8] as OfficeParagraph).text)
    }

    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
        "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val relsNs = "xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\""

    @Test
    fun aListStylePutsParagraphsInItsList() {
        val doc = """<w:document $w><w:body>
            <w:p><w:pPr><w:pStyle w:val="ListBullet"/></w:pPr><w:r><w:t>Tea</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="ListNumber"/></w:pPr><w:r><w:t>One</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="ListNumber"/></w:pPr><w:r><w:t>Two</w:t></w:r></w:p>
            </w:body></w:document>"""
        val styles = """<w:styles $w>
            <w:style w:styleId="ListBullet"><w:name w:val="List Bullet"/><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style>
            <w:style w:styleId="ListNumber"><w:name w:val="List Number"/><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr></w:style></w:styles>"""
        val numbering = """<w:numbering $w>
            <w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num></w:numbering>"""
        val file = OfficeReader.read(zip("word/document.xml" to doc, "word/styles.xml" to styles, "word/numbering.xml" to numbering)) as WordDocument
        assertEquals(listOf("•", "1.", "2."), file.blocks.map { (it as OfficeParagraph).marker })
    }

    @Test
    fun aDecksSlidesWithPlaceholdersPlacedByTheirLayout() {
        val pres = """<p:presentation $p><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>"""
        val presRels = """<Relationships $relsNs><Relationship Id="rId2" Target="slides/slide1.xml"/></Relationships>"""
        val slide = """<p:sld $p><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:t>Quarterly plan</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1270000" y="2540000"/><a:ext cx="2540000" cy="1270000"/></a:xfrm><a:solidFill><a:srgbClr val="FFEE00"/></a:solidFill></p:spPr>
              <p:txBody><a:bodyPr anchor="ctr"/><a:p><a:pPr algn="ctr"><a:buChar char="–"/></a:pPr><a:r><a:rPr sz="2000" b="1"/><a:t>Grow</a:t></a:r></a:p></p:txBody></p:sp>
            <p:grpSp><p:grpSpPr><a:xfrm><a:off x="635000" y="635000"/><a:ext cx="1270000" cy="1270000"/><a:chOff x="0" y="0"/><a:chExt cx="2540000" cy="2540000"/></a:xfrm></p:grpSpPr>
              <p:pic><p:blipFill><a:blip r:embed="rId9"/></p:blipFill><p:spPr><a:xfrm><a:off x="1270000" y="0"/><a:ext cx="1270000" cy="1270000"/></a:xfrm></p:spPr></p:pic></p:grpSp>
            </p:spTree></p:cSld></p:sld>"""
        val slideRels = """<Relationships $relsNs><Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/><Relationship Id="rId9" Target="../media/image1.png"/></Relationships>"""
        val layout = """<p:sldLayout $p><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="127000" y="254000"/><a:ext cx="1270000" cy="381000"/></a:xfrm></p:spPr></p:sp>
            </p:spTree></p:cSld></p:sldLayout>"""
        val deck = OfficeReader.read(
            zip(
                "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels,
                "ppt/slides/slide1.xml" to slide, "ppt/slides/_rels/slide1.xml.rels" to slideRels,
                "ppt/slideLayouts/slideLayout1.xml" to layout,
                binary = mapOf("ppt/media/image1.png" to byteArrayOf(9)),
            ),
        ) as Presentation
        assertEquals(960f to 540f, deck.width to deck.height)
        val shapes = deck.slides.single().shapes
        val title = shapes[0] as SlideText
        assertEquals(listOf(10f, 20f, 100f, 30f), listOf(title.x, title.y, title.width, title.height))
        assertEquals("Quarterly plan", title.paragraphs[0].text)
        assertTrue(!title.paragraphs[0].runs[0].bold)
        val box = shapes[1] as SlideText
        assertEquals(0xFFEE00, box.fill)
        assertEquals(0, box.anchor)
        assertEquals("–", box.paragraphs[0].marker)
        assertEquals(20f, box.paragraphs[0].runs[0].size)
        // In a group half its children's size, moved: (50 + (100 − 0) · 0.5, 50 + 0) and half as big.
        val picture = shapes[2] as SlidePicture
        assertEquals(listOf(100f, 50f, 50f, 50f), listOf(picture.x, picture.y, picture.width, picture.height))
    }

    @Test
    fun slideTextTakesItsLayoutsAndMastersStyle() {
        val pres = """<p:presentation $p><p:sldIdLst><p:sldId id="256" r:id="rId2"/></p:sldIdLst></p:presentation>"""
        val presRels = """<Relationships $relsNs><Relationship Id="rId2" Target="slides/slide1.xml"/></Relationships>"""
        val slide = """<p:sld $p><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:t>Hall</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:t>Point</a:t></a:r></a:p><a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>Plain</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        val slideRels = """<Relationships $relsNs><Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/></Relationships>"""
        val layout = """<p:sldLayout $p><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1270000" cy="1270000"/></a:xfrm></p:spPr>
              <p:txBody><a:bodyPr anchor="b"/><a:lstStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="6000"/></a:lvl1pPr></a:lstStyle></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="1270000"/><a:ext cx="1270000" cy="1270000"/></a:xfrm></p:spPr></p:sp>
            </p:spTree></p:cSld></p:sldLayout>"""
        val layoutRels = """<Relationships $relsNs><Relationship Id="rId1" Target="../slideMasters/slideMaster1.xml"/></Relationships>"""
        val master = """<p:sldMaster $p><p:cSld><p:spTree/></p:cSld><p:txStyles><p:titleStyle><a:lvl1pPr algn="l"/></p:titleStyle>
            <p:bodyStyle><a:lvl1pPr><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr></p:bodyStyle></p:txStyles></p:sldMaster>"""
        val deck = OfficeReader.read(
            zip(
                "ppt/presentation.xml" to pres, "ppt/_rels/presentation.xml.rels" to presRels,
                "ppt/slides/slide1.xml" to slide, "ppt/slides/_rels/slide1.xml.rels" to slideRels,
                "ppt/slideLayouts/slideLayout1.xml" to layout, "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to layoutRels,
                "ppt/slideMasters/slideMaster1.xml" to master,
            ),
        ) as Presentation
        val (title, body) = deck.slides.single().shapes.map { it as SlideText }
        // The layout's centring and size beat the master's left-aligned title style.
        assertEquals(OfficeAlign.Center, title.paragraphs[0].align)
        assertEquals(60f, title.paragraphs[0].runs[0].size)
        assertEquals(1, title.anchor)
        // The master's body style gives the bullet and size; a paragraph's own buNone drops it.
        assertEquals("•", body.paragraphs[0].marker)
        assertEquals(28f, body.paragraphs[0].runs[0].size)
        assertEquals(null, body.paragraphs[1].marker)
    }

    @Test
    fun aWorkbooksSheetsAsShown() {
        val x = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val book = """<workbook $x><sheets><sheet name="Budget" sheetId="1" r:id="rId1"/><sheet name="Secret" sheetId="2" state="hidden" r:id="rId2"/></sheets></workbook>"""
        val rels = """<Relationships $relsNs><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>"""
        val strings = """<sst $x><si><t>Item</t></si><si><r><t>Ren</t></r><r><t>t</t></r></si></sst>"""
        val styles = """<styleSheet $x><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="9"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet $x><cols><col min="1" max="1" width="20"/></cols><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1" t="inlineStr"><is><t>Note</t></is></c></row>
            <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>1250.50</v></c><c r="C2" s="1"><v>46320</v></c></row>
            <row r="4"><c r="B4" s="2"><v>0.125</v></c><c r="C4" t="b"><v>1</v></c></row>
            </sheetData></worksheet>"""
        val wb = OfficeReader.read(
            zip(
                "xl/workbook.xml" to book, "xl/_rels/workbook.xml.rels" to rels, "xl/sharedStrings.xml" to strings,
                "xl/styles.xml" to styles, "xl/worksheets/sheet1.xml" to sheet, "xl/worksheets/sheet2.xml" to sheet,
            ),
        ) as Workbook
        val s = wb.sheets.single()
        assertEquals("Budget", s.name)
        assertEquals(
            listOf(
                listOf("Item", "", "Note"),
                listOf("Rent", "1250.5", "2026-10-25"),
                listOf("", "", ""),
                listOf("", "12.5%", "TRUE"),
            ),
            s.rows,
        )
        assertEquals(20f, s.widths[0])
    }

    @Test
    fun ourOwnWordExportReadsBack() {
        val out = ByteArrayOutputStream()
        DocxWriter.write("Notes", listOf("First page line one\n\nSecond paragraph", "Page two"), out)
        val doc = OfficeReader.read(ByteArrayInputStream(out.toByteArray())) as WordDocument
        val text = doc.blocks.filterIsInstance<OfficeParagraph>().joinToString("|") { it.text }
        assertTrue(text, text.contains("First page line one") && text.contains("Second paragraph") && text.contains("Page two"))
    }

    @Test
    fun aZipThatUnpacksTooFarIsRefused() {
        val huge = ByteArray(65 * 1024 * 1024)
        val result = runCatching { OfficeReader.read(zip("word/document.xml" to "<x/>", binary = mapOf("word/media/big.bin" to huge))) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isFailure)
    }

    @Test
    fun handlesKnowsTheFormats() {
        assertTrue(OfficeReader.handles("Report.DOCX", null))
        assertTrue(OfficeReader.handles(null, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        assertTrue(!OfficeReader.handles("old.doc", "application/msword"))
    }
}
