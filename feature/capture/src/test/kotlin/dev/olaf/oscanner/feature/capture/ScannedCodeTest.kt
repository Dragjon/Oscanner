package dev.olaf.oscanner.feature.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScannedCodeTest {

    @Test
    fun recognizesKinds() {
        assertEquals(CodeKind.Link, ScannedCode("https://example.org", "QR_CODE").kind)
        assertEquals(CodeKind.Product, ScannedCode("4006381333931", "EAN_13").kind)
        assertEquals(CodeKind.Text, ScannedCode("hello", "QR_CODE").kind)
        assertEquals(CodeKind.Wifi, ScannedCode("WIFI:T:WPA;S:Home;P:pw;;", "QR_CODE").kind)
    }

    @Test
    fun readsWifiWithEscapes() {
        val code = ScannedCode("""WIFI:T:WPA;S:My\;Net;P:a\:b\\c;;""", "QR_CODE")
        assertEquals("My;Net" to "a:b\\c", code.wifi)
    }

    @Test
    fun openNetworkHasNoPassword() {
        assertEquals("Cafe" to null, ScannedCode("WIFI:T:nopass;S:Cafe;;", "QR_CODE").wifi)
        assertNull(ScannedCode("plain", "QR_CODE").wifi)
    }
}
