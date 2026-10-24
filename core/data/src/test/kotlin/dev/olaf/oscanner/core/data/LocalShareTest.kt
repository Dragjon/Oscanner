package dev.olaf.oscanner.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalShareTest {

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            val code = c.responseCode
            code to ((if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            c.disconnect()
        }
    }

    private fun pdf(): File = File.createTempFile("share", ".pdf").apply {
        deleteOnExit()
        writeBytes(ByteArray(300_000) { (it % 251).toByte() })
    }

    @Test
    fun servesThePageAndTheFileWithTheSecretOnly() {
        val file = pdf()
        val sent = CountDownLatch(1)
        LocalShare(file, "Lease <2026>.pdf", "Lease & keys", "application/pdf", InetAddress.getLoopbackAddress(), onSent = { sent.countDown() }).use { share ->
            val (code, page) = get(share.url)
            assertEquals(200, code)
            val html = String(page)
            assertTrue(html, html.contains("Lease &amp; keys") && html.contains("/file\""))
            val (fileCode, bytes) = get(share.url + "/file")
            assertEquals(200, fileCode)
            assertArrayEquals(file.readBytes(), bytes)
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            val c = URL(share.url + "/file").openConnection() as HttpURLConnection
            assertTrue(c.getHeaderField("Content-Disposition").startsWith("attachment; filename=\"Lease <2026>.pdf\""))
            assertEquals("no-referrer", c.getHeaderField("Referrer-Policy"))
            c.disconnect()

            // Typed in capitals, the secret still works.
            val secret = share.url.substringAfterLast('/')
            assertEquals(8, secret.length)
            assertEquals(200, get(share.url.removeSuffix(secret) + secret.uppercase()).first)

            // The address without the secret, or with a wrong one, shows nothing.
            val root = share.url.substringBeforeLast('/') + "/"
            assertEquals(404, get(root).first)
            assertEquals(404, get(root + "AAAAAAAAAAAAAAAAAAAAAA/file").first)
        }
    }

    @Test
    fun stopsAfterTooManyWrongGuesses() {
        LocalShare(pdf(), "a.pdf", "a", "application/pdf", InetAddress.getLoopbackAddress()).use { share ->
            val root = share.url.substringBeforeLast('/') + "/"
            repeat(LocalShare.MAX_MISSES) { get(root + "guess$it/") }
            assertTrue(share.closed)
        }
    }
}
