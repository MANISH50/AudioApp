package io.screenlink.app.net

import android.annotation.SuppressLint
import io.screenlink.app.identity.DeviceIdentity
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Laptop certificate check. With a pinned fingerprint (a paired laptop) anything else is rejected during the handshake.
 * Without one (first pairing) the certificate is accepted only so the pairing code can be shown; nothing but HELLO and
 * the pairing exchange happens until the user confirms the codes match.
 *
 * A custom trust manager is deliberate: laptops have self-signed certificates, so trust comes from the pinned
 * fingerprint (established by the user comparing codes), not from a certificate authority.
 */
@SuppressLint("CustomX509TrustManager")
class PinningTrustManager(private val pinned: ByteArray?) : X509TrustManager {
    @Volatile var serverFingerprint: ByteArray? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no laptop certificate")
        val fp = Pairing.fingerprint(leaf.encoded)
        if (pinned != null && !MessageDigest.isEqual(fp, pinned)) {
            throw CertificateException("laptop identity changed")
        }
        val alg = leaf.publicKey.algorithm
        if (alg != "EC" && alg != "RSA") throw CertificateException("unsupported key type")
        serverFingerprint = fp
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client role only")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** An open, authenticated TLS connection to a laptop. */
class SecureSocket(val socket: SSLSocket, val laptopFingerprint: ByteArray, val phoneFingerprint: ByteArray)

object SecureChannel {
    private const val CONNECT_TIMEOUT_MS = 5_000
    const val READ_TIMEOUT_MS = 20_000

    /** Connects with mutual TLS (1.3, or 1.2 on Windows 10, which has no TLS 1.3 server). Blocking. */
    fun connect(host: String, port: Int, pinned: ByteArray?): SecureSocket {
        val trust = PinningTrustManager(pinned)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(DeviceIdentity.keyManager()), arrayOf(trust), SecureRandom())
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = READ_TIMEOUT_MS
            val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            ssl.enabledProtocols = ssl.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            ssl.startHandshake()
            val fp = trust.serverFingerprint ?: throw CertificateException("handshake without certificate")
            return SecureSocket(ssl, fp, Pairing.fingerprint(DeviceIdentity.certificate().encoded))
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }
}
