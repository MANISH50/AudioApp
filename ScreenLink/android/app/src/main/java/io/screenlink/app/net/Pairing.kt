package io.screenlink.app.net

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pairing by numeric comparison (like Bluetooth): after the TLS handshake both devices show a 6-digit code computed
 * from both certificate fingerprints. A machine-in-the-middle has to present its own certificate to each side, which
 * changes the fingerprints and therefore the code, so the user sees two different numbers.
 */
object Pairing {
    private val LABEL = "ScreenLink-pair-v1".toByteArray(StandardCharsets.US_ASCII)

    fun fingerprint(der: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(der)

    /** 6 digits, zero-padded: SHA-256(label ‖ laptopFingerprint ‖ phoneFingerprint), first 4 bytes mod 1 000 000. */
    fun code(laptopFingerprint: ByteArray, phoneFingerprint: ByteArray): String {
        require(laptopFingerprint.size == 32 && phoneFingerprint.size == 32)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(LABEL)
        md.update(laptopFingerprint)
        md.update(phoneFingerprint)
        val h = md.digest()
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (v % 1_000_000).toString().padStart(6, '0')
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(s: String): ByteArray? {
        if (s.length % 2 != 0) return null
        return try {
            ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Groups a fingerprint for display, e.g. "3F2A 91C0 …" (first 8 bytes). */
    fun shortFingerprint(fp: ByteArray): String =
        toHex(fp.copyOf(minOf(8, fp.size))).uppercase().chunked(4).joinToString(" ")
}
