package dev.olaf.oscanner.core.data

import dev.olaf.oscanner.core.pdf.PdfWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DocumentNamerTest {

    private val namer = DocumentNamer(monthFirst = false, formatDate = { "${it.dayOfMonth}/${it.monthValue}/${it.year}" })

    /** A line of words at [top], letters [height] tall, starting at [left]; all as page fractions. */
    private fun line(text: String, top: Float, height: Float = 0.015f, left: Float = 0.1f): List<PdfWord> {
        var x = left
        val w = height * 0.55f
        return text.split(' ').map { word ->
            PdfWord(word, x, top, x + word.length * w, top + height).also { x += (word.length + 1) * w }
        }
    }

    @Test
    fun aTillReceiptIsNamedForTheShopAndDay() {
        val words = line("TESCO", 0.04f, 0.03f, 0.35f) +
            line("Tesco Stores Ltd", 0.08f) +
            line("123 High Street, Leeds LS1 4AB", 0.10f) +
            line("VAT No: GB 220 4302 31", 0.12f) +
            line("Milk 2L 1.45", 0.2f) + line("Bread 1.10", 0.22f) + line("Bananas 0.95", 0.24f) + line("Coffee 4.50", 0.26f) +
            line("SUBTOTAL 8.00", 0.3f) + line("TOTAL 8.00", 0.32f) + line("VISA 8.00", 0.34f) +
            line("24/10/2026 14:32 Till 4", 0.4f) + line("Thank you for shopping with us", 0.45f)
        assertEquals("Receipt – Tesco – 24/10/2026", namer.name(words))
    }

    @Test
    fun anInvoiceTakesTheSellerNotTheCustomer() {
        val words = line("Acme Plumbing Ltd", 0.05f, 0.025f) +
            line("Unit 4, Mill Road, Bristol", 0.09f) +
            line("TAX INVOICE", 0.15f, 0.02f, 0.6f) +
            line("Invoice date: 12 September 2026", 0.19f, left = 0.6f) +
            line("Due date: 12 October 2026", 0.21f, left = 0.6f) +
            line("Bill to: Sam Jones", 0.25f) +
            line("Description Qty Unit price Amount", 0.35f) +
            line("Replace boiler valve 1 120.00 120.00", 0.38f) +
            line("Amount due 144.00", 0.5f)
        assertEquals("Invoice – Acme Plumbing – 12/9/2026", namer.name(words))
    }

    @Test
    fun aLetterFromAnOfficeIsALetterFromIt() {
        val words = line("HM Revenue & Customs", 0.04f, 0.025f) +
            line("PAYE and Self Assessment", 0.08f) +
            line("BX9 1AS", 0.10f) +
            line("Mr S Jones", 0.18f) + line("4 Elm Close", 0.20f) +
            line("3 March 2026", 0.25f, left = 0.7f) +
            line("Dear Mr Jones", 0.3f) +
            line("We have changed your tax code for the year.", 0.33f) +
            line("Yours sincerely", 0.6f)
        assertEquals("Letter from HM Revenue & Customs – 3/3/2026", namer.name(words))
    }

    @Test
    fun aLetterWithASubjectIsNamedForIt() {
        val words = line("Dear Ms Patel", 0.2f) +
            line("Re: Your application for a parking permit", 0.24f) +
            line("Thank you for your application.", 0.28f) +
            line("Kind regards", 0.5f)
        assertEquals("Letter – Your application for a parking permit", namer.name(words))
    }

    @Test
    fun aBankStatementIsNamedForTheBank() {
        val words = line("NORTHERN BANK", 0.04f, 0.03f) +
            line("Statement of account", 0.1f) +
            line("Account number 12345678 Sort code 12-34-56", 0.13f) +
            line("Statement date 30/09/2026", 0.15f) +
            line("Opening balance 1,204.10", 0.2f) +
            line("Closing balance 980.55", 0.22f)
        assertEquals("Statement – Northern Bank – 30/9/2026", namer.name(words))
    }

    @Test
    fun anAgreementKeepsItsOwnHeading() {
        val words = line("Assured Shorthold Tenancy Agreement", 0.05f, 0.03f) +
            line("This agreement is made between the parties below.", 0.12f) +
            line("The landlord and the tenant agree to the terms and conditions.", 0.14f) +
            line("Signature", 0.8f)
        assertEquals("Assured Shorthold Tenancy Agreement", namer.name(words))
    }

    @Test
    fun ordinaryPagesFallBackToTheirHeading() {
        val words = line("Garden Club Report", 0.05f, 0.03f) +
            line("The club met in May and planted the new beds by the gate.", 0.12f) +
            line("Everyone brought tools and the work was done by noon.", 0.14f)
        assertEquals("Garden Club Report", namer.name(words))
        assertNull(namer.name(emptyList()))
    }

    @Test
    fun datesInTheirUsualForms() {
        assertEquals(LocalDate.of(2026, 10, 24), namer.anyDate("Date: 24/10/2026"))
        assertEquals(LocalDate.of(2026, 4, 3), namer.anyDate("03/04/2026"))
        assertEquals(LocalDate.of(2026, 3, 4), DocumentNamer(monthFirst = true).anyDate("03/04/2026"))
        assertEquals(LocalDate.of(2026, 10, 24), DocumentNamer(monthFirst = true).anyDate("24/10/26"))
        assertEquals(LocalDate.of(2026, 10, 24), namer.anyDate("2026-10-24"))
        assertEquals(LocalDate.of(2026, 10, 24), namer.anyDate("24th Oct, 2026"))
        assertEquals(LocalDate.of(2026, 10, 24), namer.anyDate("October 24, 2026"))
        assertNull(namer.anyDate("Total 12.50"))
        assertNull(namer.anyDate("32/13/2026"))
    }

    @Test
    fun namesAreTidied() {
        assertEquals("Tesco Stores", namer.tidyName("TESCO STORES LTD"))
        assertEquals("Acme Plumbing", namer.tidyName("Acme Plumbing Ltd."))
        assertEquals("HSBC Bank", namer.tidyName("HSBC BANK PLC"))
    }
}
