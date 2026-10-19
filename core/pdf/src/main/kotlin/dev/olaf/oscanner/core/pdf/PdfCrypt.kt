package dev.olaf.oscanner.core.pdf

import java.security.MessageDigest
import java.text.Normalizer
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * PDF password protection with AES-256, the PDF 2.0 standard security handler (ISO 32000-2
 * §7.6.4, revision 6, crypt filter AESV3). Every string and stream is encrypted with one random
 * 256-bit file key; the password unlocks that key through the salted, iterated hash of
 * Algorithm 2.B. Opening the file needs the password; once open, nothing is restricted.
 */
internal class PdfCrypt(password: String, private val random: SecureRandom = SecureRandom()) {

    private val fileKey = ByteArray(32).also(random::nextBytes)
    // The spec asks for SASLprep; NFKC normalization is its core, so "é" typed two ways matches.
    private val passwordBytes = Normalizer.normalize(password, Normalizer.Form.NFKC)
        .toByteArray(Charsets.UTF_8).take(127).toByteArray()

    /** The /Encrypt dictionary body. */
    val dictionary: String

    init {
        val userValidationSalt = randomBytes(8)
        val userKeySalt = randomBytes(8)
        val u = hash(passwordBytes, userValidationSalt, EMPTY) + userValidationSalt + userKeySalt
        val ue = aesNoPadding(hash(passwordBytes, userKeySalt, EMPTY), fileKey)

        // The owner password is the same one: whoever can open the file may do anything with it.
        val ownerValidationSalt = randomBytes(8)
        val ownerKeySalt = randomBytes(8)
        val o = hash(passwordBytes, ownerValidationSalt, u) + ownerValidationSalt + ownerKeySalt
        val oe = aesNoPadding(hash(passwordBytes, ownerKeySalt, u), fileKey)

        val perms = ByteArray(16)
        for (i in 0 until 4) perms[i] = (PERMISSIONS shr (8 * i)).toByte()
        for (i in 4 until 8) perms[i] = 0xFF.toByte()
        perms[8] = 'T'.code.toByte()
        perms[9] = 'a'.code.toByte()
        perms[10] = 'd'.code.toByte()
        perms[11] = 'b'.code.toByte()
        randomBytes(4).copyInto(perms, 12)
        val permsEncrypted = Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"))
            doFinal(perms)
        }

        dictionary = "<< /Filter /Standard /V 5 /R 6 /Length 256 " +
            "/CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF " +
            "/O ${hex(o)} /U ${hex(u)} /OE ${hex(oe)} /UE ${hex(ue)} /P $PERMISSIONS /Perms ${hex(permsEncrypted)} " +
            "/EncryptMetadata true >>"
    }

    /** A stream's bytes as stored: a random IV, then AES-256-CBC with PKCS#7 padding. */
    fun encrypt(data: ByteArray): ByteArray {
        val iv = randomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(data)
    }

    /** A string object, encrypted and written as hex. */
    fun string(data: ByteArray): String = hex(encrypt(data))

    private fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    companion object {
        private val EMPTY = ByteArray(0)
        /** All permissions granted (bits 3–12 set, reserved high bits set as the spec asks). */
        const val PERMISSIONS = -4

        fun hex(bytes: ByteArray): String =
            "<" + bytes.joinToString("") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) } + ">"

        /** Algorithm 2.B: the revision 6 password hash. [userKey] is U's 48 bytes for owner checks. */
        internal fun hash(password: ByteArray, salt: ByteArray, userKey: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(password + salt + userKey)
            var round = 0
            while (true) {
                val block = password + k + userKey
                val k1 = ByteArray(block.size * 64)
                for (i in 0 until 64) block.copyInto(k1, i * block.size)
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOfRange(0, 16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = cipher.doFinal(k1)
                val mod = e.copyOfRange(0, 16).sumOf { it.toInt() and 0xFF } % 3
                k = MessageDigest.getInstance(
                    when (mod) {
                        0 -> "SHA-256"
                        1 -> "SHA-384"
                        else -> "SHA-512"
                    },
                ).digest(e)
                round++
                if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOfRange(0, 32)
        }

        private fun aesNoPadding(key: ByteArray, data: ByteArray): ByteArray =
            Cipher.getInstance("AES/CBC/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                doFinal(data)
            }
    }
}
