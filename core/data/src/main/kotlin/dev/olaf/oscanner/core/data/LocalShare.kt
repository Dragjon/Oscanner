package dev.olaf.oscanner.core.data

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Hands one file to a computer on the same Wi-Fi, with no account and nothing in between: a
 * small web server on the phone's own address serves a page with a download link, at a link
 * holding a random secret, for as long as it's open. Anything asked for without the secret
 * gets "not found", and after [MAX_MISSES] of those the server stops.
 *
 * The connection is plain HTTP inside the local network: anyone on it who has the link can
 * download the file while it's shared, which the screen offering it says.
 */
class LocalShare(
    private val file: File,
    /** The file's name on the computer, such as "Lease.pdf". */
    private val name: String,
    private val title: String,
    private val mime: String,
    address: InetAddress,
    /** Called on a server thread each time the file has been sent in full, with the computer's address. */
    private val onSent: (String) -> Unit = {},
) : Closeable {

    private val secret = SecureRandom().let { random -> String(CharArray(SECRET_LENGTH) { SECRET_LETTERS[random.nextInt(SECRET_LETTERS.length)] }) }
    private val server = ServerSocket(0, BACKLOG, address)
    private val misses = AtomicInteger()
    private val busy = Semaphore(MAX_CLIENTS)

    /** What to type in the computer's browser. */
    val url = "http://${address.hostAddress}:${server.localPort}/$secret"

    @Volatile
    var closed = false
        private set

    init {
        thread(name = "LocalShare", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                // A few computers at once at most; more are turned away, not queued.
                if (!busy.tryAcquire()) {
                    runCatching { socket.close() }
                    continue
                }
                thread(name = "LocalShare client", isDaemon = true) {
                    try {
                        socket.use { runCatching { answer(it) } }
                    } finally {
                        busy.release()
                    }
                }
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }

    private fun answer(socket: Socket) {
        socket.soTimeout = TIMEOUT_MS
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val requestLine = readLine(input) ?: return
        // The headers aren't needed; they're read so the browser sees its request taken.
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1)?.substringBefore('?') ?: ""
        if (method != "GET" && method != "HEAD") return respond(out, 405, "text/plain", "Not allowed".toByteArray(), head = false)
        val head = method == "HEAD"
        val segments = path.trim('/').split('/')
        if (!matches(segments.firstOrNull().orEmpty())) {
            if (misses.incrementAndGet() >= MAX_MISSES) close()
            return respond(out, 404, "text/plain", "Not found".toByteArray(), head)
        }
        when (segments.drop(1)) {
            emptyList<String>() -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray(), head)
            listOf("file") -> {
                respondHeaders(
                    out, 200, mime, file.length(),
                    "Content-Disposition: attachment; filename=\"${asciiName()}\"; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}",
                )
                if (!head) {
                    file.inputStream().use { it.copyTo(out) }
                    out.flush()
                    onSent(socket.inetAddress.hostAddress ?: "")
                }
            }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray(), head)
        }
    }

    /**
     * Compares in constant time, so the secret can't be guessed a letter at a time. Capitals
     * count as small letters, as someone typing it may hold Shift or Caps Lock.
     */
    private fun matches(candidate: String) = MessageDigest.isEqual(candidate.lowercase().toByteArray(), secret.toByteArray())

    private fun page(): String {
        val t = escape(title)
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>$t</title><style>body{font:16px/1.5 system-ui,sans-serif;max-width:32em;margin:12vh auto;padding:0 16px;color:#1d1b1a;background:#faf8f5}""" +
            """h1{font-size:1.5em;margin:0 0 .25em}p{color:#5c5652}a.get{display:inline-block;margin:1em 0;padding:.75em 1.5em;border-radius:999px;""" +
            """background:#c2410c;color:#fff;text-decoration:none;font-weight:600}@media(prefers-color-scheme:dark){body{background:#1a1817;color:#f2eeea}p{color:#b8b0aa}}</style>""" +
            """</head><body><h1>$t</h1><p>${escape(name)} · ${sizeText(file.length())}</p>""" +
            """<a class="get" href="/$secret/file" download="${escape(asciiName())}">Download</a>""" +
            """<p>Shared from Oscanner on a phone on this network. The link stops working when the phone stops sharing.</p></body></html>"""
    }

    private fun asciiName() = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, head: Boolean) {
        respondHeaders(out, status, type, body.size.toLong())
        if (!head) out.write(body)
        out.flush()
    }

    private fun respondHeaders(out: OutputStream, status: Int, type: String, length: Long, vararg extra: String) {
        val reason = when (status) {
            200 -> "OK"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $type\r\n")
            append("Content-Length: $length\r\n")
            append("Connection: close\r\n")
            // The secret is in the link: nothing should keep it or pass it on.
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("\r\n")
        }
        out.write(headers.toByteArray(Charsets.ISO_8859_1))
    }

    /** One line of the request, without its line end; null at the end or past [MAX_LINE]. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            line.append(c.toChar())
        }
        return null
    }

    companion object {
        /**
         * The secret is short enough to type from the screen: 8 letters of 31 (no 0/o, 1/l/i
         * to mix up), about 2⁴⁰ choices. Against [MAX_MISSES] guesses before the server stops,
         * that's a chance of one in twenty billion.
         */
        private const val SECRET_LENGTH = 8
        private const val SECRET_LETTERS = "abcdefghjkmnpqrstuvwxyz23456789"
        private const val BACKLOG = 8
        private const val MAX_CLIENTS = 4
        private const val TIMEOUT_MS = 15_000
        private const val MAX_LINE = 8192
        const val MAX_MISSES = 50

        /**
         * The phone's address on its Wi-Fi network (or the hotspot it runs), the one a computer
         * beside it can reach: a private IPv4 address on an interface that's up. Wi-Fi interfaces
         * come first; mobile data's are never private in this sense, so they don't show up.
         */
        fun localAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()

        internal fun escape(text: String) = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        private fun sizeText(bytes: Long) = when {
            bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1_000_000.0)
            else -> "${(bytes + 999) / 1000} kB"
        }
    }
}
