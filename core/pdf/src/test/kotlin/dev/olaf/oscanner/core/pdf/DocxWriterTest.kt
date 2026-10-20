package dev.olaf.oscanner.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class DocxWriterTest {

    @Test
    fun joinsLinesAndHyphenatedWords() {
        val text = "Dear tenant, the rent\nfor Septem-\nber is due.\n\nKind regards,\nThe office"
        assertEquals(
            listOf("Dear tenant, the rent for September is due.", "Kind regards, The office"),
            DocxWriter.paragraphs(text),
        )
    }

    @Test
    fun writesAValidPackage() {
        val out = ByteArrayOutputStream()
        DocxWriter.write("Bill <Sept> & more", listOf("Électricité\n\nTotal 84.20", "Page two"), out)
        val bytes = out.toByteArray()

        val parts = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { parts[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(parts.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml")))
        val doc = parts.getValue("word/document.xml")
        assertTrue(doc.contains("Bill &lt;Sept&gt; &amp; more"))
        assertTrue(doc.contains("Électricité"))
        assertTrue(doc.contains("""<w:br w:type="page"/>"""))
        // Checked by python-docx from the build folder.
        File("build/test-output").apply { mkdirs() }.resolve("text.docx").writeBytes(bytes)
    }
}
