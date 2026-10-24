package dev.olaf.oscanner.core.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfImportTest {

    @Test
    fun textInLettersIsKept() {
        assertTrue(PdfImport.readable("Quarterly report\r\nSales rose by 12% — €1,250.00"))
        assertTrue(PdfImport.readable("Prix unitaire : 3,50 €. Größe: XL. 東京都"))
    }

    @Test
    fun fontsWithoutALetterMapAreReadInstead() {
        assertFalse(PdfImport.readable(" "))
        assertFalse(PdfImport.readable("��� ab"))
        assertFalse(PdfImport.readable("  \r\n "))
    }
}
