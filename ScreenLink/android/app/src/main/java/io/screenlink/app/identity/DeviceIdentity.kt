package io.screenlink.app.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * This phone's identity for mutual TLS: an EC P-256 key generated inside Android Keystore (hardware-backed where the
 * device has it). The private key can't be exported or read by anyone, including this app; Keystore only signs with it.
 * Its self-signed certificate is what the laptop pins at pairing.
 */
object DeviceIdentity {
    private const val ALIAS = "screenlink-identity-v1"
    private const val PROVIDER = "AndroidKeyStore"

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    fun certificate(): X509Certificate {
        val ks = keyStore()
        if (!ks.containsAlias(ALIAS)) generate()
        return ks.getCertificate(ALIAS) as X509Certificate
    }

    private fun privateKey(): PrivateKey {
        certificate()
        return keyStore().getKey(ALIAS, null) as PrivateKey
    }

    private fun generate() {
        val serial = BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setCertificateSubject(X500Principal("CN=ScreenLink Android"))
            .setCertificateSerialNumber(serial)
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 24L * 3600 * 1000))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 365 * 24 * 3600 * 1000))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    /** Deletes the key: the next connection creates a new identity and every laptop must pair again. */
    @Synchronized
    fun reset() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    /** Presents the Keystore key as the TLS client certificate. */
    fun keyManager(): X509ExtendedKeyManager {
        val cert = certificate()
        val key = privateKey()
        return object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?) = if (alias == ALIAS) key else null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
    }
}
