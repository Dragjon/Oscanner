package dev.olaf.oscanner.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class PageLayoutTest {

    /** Words of a line from [left], each [w] wide with small gaps, letters [h] tall. */
    private fun line(text: String, left: Float, top: Float, h: Float = 0.02f, w: Float = 0.08f): List<PdfWord> =
        text.split(' ').mapIndexed { i, word -> PdfWord(word, left + i * (w + 0.01f), top, left + i * (w + 0.01f) + w, top + h) }

    @Test
    fun buildsHeadingsAndParagraphs() {
        // Centred over the text block, which runs from 0.1 to 0.81.
        val words = line("Annual report", 0.27f, 0.05f, h = 0.045f, w = 0.18f) +
            line("The year went well for the whole", 0.1f, 0.15f) +
            line("team and the shop grew a lot", 0.1f, 0.175f) +
            line("Next year we plan more of the same", 0.1f, 0.24f)
        val blocks = PageLayout.paragraphs(words)
        assertEquals(3, blocks.size)
        assertEquals(ParagraphStyle.Heading1, blocks[0].style)
        assertTrue(blocks[0].centered)
        assertEquals("The year went well for the whole team and the shop grew a lot", blocks[1].text)
        assertEquals(ParagraphStyle.Body, blocks[1].style)
        assertFalse(blocks[1].centered)
        assertEquals("Next year we plan more of the same", blocks[2].text)
    }

    @Test
    fun aTitleWiderThanARaggedColumnStillLeadsIt() {
        // Like a letter: the title centred on the page and wider than the body text, lines set
        // wide apart (gap about 0.85 of a letter), paragraphs a line further.
        val h = 0.013f
        val pitch = 0.024f
        val words = line("Garden Club Report", 0.29f, 0.07f, h = 0.03f, w = 0.13f) +
            line("This spring the club planted", 0.09f, 0.15f, h, w = 0.085f) +
            line("the river path and repaired", 0.09f, 0.15f + pitch, h, w = 0.085f) +
            line("before the first frost came", 0.09f, 0.15f + 2 * pitch, h, w = 0.08f) +
            line("Members met every second", 0.09f, 0.15f + 3 * pitch + 0.023f, h, w = 0.1f) +
            line("shared seeds and cuttings", 0.09f, 0.15f + 4 * pitch + 0.023f, h, w = 0.1f)
        val blocks = PageLayout.paragraphs(words)
        assertEquals(listOf(ParagraphStyle.Heading1, ParagraphStyle.Body, ParagraphStyle.Body), blocks.map { it.style })
        assertTrue(blocks[0].centered)
        assertTrue(blocks[1].text.startsWith("This spring") && blocks[1].text.endsWith("frost came"))
    }

    @Test
    fun aShortLineEndsItsParagraph() {
        val words = line("Dear Sam,", 0.1f, 0.1f) + line("Thanks for the parcel which came today", 0.1f, 0.125f)
        assertEquals(2, PageLayout.paragraphs(words).size)
    }

    /** A newspaper-like page: a title across, two columns of [rows] lines each, the right column a little lower. */
    private fun twoColumns(rows: Int = 8, gap: Float = 0.025f): List<PdfWord> {
        val words = line("City news", 0.36f, 0.04f, h = 0.04f, w = 0.13f).toMutableList()
        val colWidth = (0.9f - 0.08f - gap) / 2
        // Five words a line, filling the column.
        val w = (colWidth - 4 * 0.01f) / 5
        fun col(name: String, left: Float, top: Float) = (0 until rows).flatMap { i -> line("$name$i a b c d", left, top + i * 0.025f, h = 0.015f, w = w) }
        words += col("L", 0.08f, 0.12f)
        words += col("R", 0.08f + colWidth + gap, 0.127f)
        return words
    }

    @Test
    fun readsTwoColumnsDownOneThenTheOther() {
        val blocks = PageLayout.paragraphs(twoColumns())
        assertEquals("City news", blocks[0].text)
        assertEquals(0, blocks[0].column)
        val texts = blocks.drop(1).map { it.text }
        // Each column one paragraph, left first, its lines in order.
        assertEquals(2, texts.size)
        assertTrue(texts[0], texts[0].startsWith("L0 ") && texts[0].endsWith("L7 a b c d") && !texts[0].contains("R"))
        assertTrue(texts[1], texts[1].startsWith("R0 ") && !texts[1].contains("L"))
        assertEquals(listOf(1, 2), blocks.drop(1).map { it.column })
    }

    @Test
    fun columnTextReadsEachColumnDown() {
        val text = PageLayout.columnText(twoColumns(rows = 5))!!.lines()
        assertEquals(listOf("City news", "", "L0 a b c d", "L1 a b c d"), text.take(4))
        assertEquals(listOf("L4 a b c d", "", "R0 a b c d"), text.subList(6, 9))
        val oneColumn = (0 until 12).flatMap { i -> line("one long line of body text across the page", 0.1f, 0.1f + i * 0.025f, h = 0.015f, w = 0.08f) }
        assertEquals(null, PageLayout.columnText(oneColumn))
    }

    @Test
    fun aFormsLabelsAndAnswersStayRowByRow() {
        // Short labels on the left, long answers lined up on the right: not two columns.
        val words = (0 until 8).flatMap { i ->
            line("Label$i:", 0.08f, 0.1f + i * 0.04f, h = 0.015f, w = 0.1f) +
                line("an answer that runs on and on here", 0.4f, 0.1f + i * 0.04f, h = 0.015f, w = 0.06f)
        }
        val blocks = PageLayout.paragraphs(words)
        assertTrue(blocks.all { it.column == 0 })
        assertEquals("Label0:", blocks[0].text)
        assertTrue(blocks[1].text.startsWith("an answer"))
        assertEquals("Label1:", blocks[2].text)
    }

    @Test
    fun oneColumnPagesHaveNoColumns() {
        val words = (0 until 12).flatMap { i -> line("one long line of body text across the page", 0.1f, 0.1f + i * 0.025f, h = 0.015f, w = 0.08f) }
        assertTrue(PageLayout.paragraphs(words).all { it.column == 0 })
    }

    @Test
    fun writesColumnsAsWordSections() {
        val docx = ByteArrayOutputStream().also { DocxWriter.writeLayout("News", listOf(PageLayout.blocks(twoColumns())), it) }.toByteArray()
        val xml = parts(docx).getValue("word/document.xml")
        assertTrue(xml.contains("""<w:cols w:num="2""""))
        assertTrue(xml.contains("""<w:br w:type="column"/>"""))
        // The title's section ends before the columns; the columns' section ends the body.
        assertTrue(xml.indexOf("""<w:cols w:num="1"""") < xml.indexOf("L0 "))
        assertTrue(xml.indexOf("""<w:br w:type="column"/>""") in xml.indexOf("L7 ")..xml.indexOf("R0 "))
        File("build/test-output").apply { mkdirs() }.resolve("columns.docx").writeBytes(docx)
    }

    @Test
    fun writesWordAndPowerPointFilesOfficeCanOpen() {
        // A tiny real JPEG (1x1, white), so readers that decode pictures are happy.
        val jpeg = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/yQALCAABAAEBAREA/8wABgAQEAX/2gAIAQEAAD8A0s8g/9k=",
        )
        val blocks: List<LayoutBlock> = PageLayout.paragraphs(
            line("Annual report", 0.225f, 0.05f, h = 0.045f, w = 0.18f) + line("The year went well & grew <a lot>", 0.1f, 0.15f),
        ) + LayoutPicture(jpeg, 400, 300, 0.5f, 0.3f)
        val docx = ByteArrayOutputStream().also { DocxWriter.writeLayout("Report", listOf(blocks, blocks), it) }.toByteArray()
        val pptx = ByteArrayOutputStream().also {
            PptxWriter.write("Report", listOf(PptxSlide(jpeg, 1600, 900, "Quarterly results\nRevenue up"), PptxSlide(jpeg, 900, 1600, "")), it)
        }.toByteArray()

        val docParts = parts(docx)
        assertTrue(docParts.getValue("word/document.xml").contains("Heading1"))
        assertTrue(docParts.containsKey("word/media/image2.jpeg"))
        assertTrue(parts(pptx).getValue("ppt/slides/slide1.xml").contains("Quarterly results"))
        // Opened by python-docx and python-pptx from the build folder.
        File("build/test-output").apply { mkdirs() }.apply {
            resolve("layout.docx").writeBytes(docx)
            resolve("deck.pptx").writeBytes(pptx)
        }
    }

    private fun parts(bytes: ByteArray): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().toString(Charsets.ISO_8859_1) }
        }
        return parts
    }
}
