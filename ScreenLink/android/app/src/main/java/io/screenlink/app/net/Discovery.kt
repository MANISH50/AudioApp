package io.screenlink.app.net

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

data class DiscoveredLaptop(val host: String, val port: Int, val fingerprintHex: String, val name: String)

/**
 * Finds ScreenLink laptops on the local network: a UDP broadcast "SCREENLINK?1" to port 47421, each laptop answers
 * "SCREENLINK!1\n<tcp port>\n<certificate fingerprint hex>\n<name>". The answer is only a hint for the list: the
 * connection itself is authenticated by TLS + pairing, so a fake answer can't get anything from the phone.
 */
object Discovery {
    const val QUERY = "SCREENLINK?1"
    private const val REPLY_PREFIX = "SCREENLINK!1\n"

    fun parseReply(host: String, data: ByteArray, length: Int): DiscoveredLaptop? {
        if (length > 512) return null
        val text = String(data, 0, length, StandardCharsets.UTF_8)
        if (!text.startsWith(REPLY_PREFIX)) return null
        val parts = text.removePrefix(REPLY_PREFIX).split('\n')
        if (parts.size < 3) return null
        val port = parts[0].toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
        val fp = parts[1].lowercase().takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } ?: return null
        val name = parts[2].filter { !it.isISOControl() }.take(64).ifBlank { host }
        return DiscoveredLaptop(host, port, fp, name)
    }

    /** IPv4 broadcast address of each active network plus 255.255.255.255. */
    private fun broadcastTargets(context: Context): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return out
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return out
        for (la in lp.linkAddresses) {
            val addr = la.address as? Inet4Address ?: continue
            val prefix = la.prefixLength
            if (prefix !in 8..30) continue
            val ip = addr.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val mask = -1 shl (32 - prefix)
            val bc = ip or mask.inv()
            out += InetAddress.getByAddress(byteArrayOf((bc ushr 24).toByte(), (bc ushr 16).toByte(), (bc ushr 8).toByte(), bc.toByte()))
        }
        return out.distinct()
    }

    /** Blocking scan, about [durationMs] long. Call off the main thread. */
    fun scan(context: Context, durationMs: Long = 1500): List<DiscoveredLaptop> {
        val found = LinkedHashMap<String, DiscoveredLaptop>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val query = QUERY.toByteArray(StandardCharsets.US_ASCII)
            val targets = runCatching { broadcastTargets(context) }.getOrDefault(listOf(InetAddress.getByName("255.255.255.255")))
            val buf = ByteArray(600)
            val end = System.currentTimeMillis() + durationMs
            var nextSend = 0L
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    for (t in targets) runCatching { socket.send(DatagramPacket(query, query.size, t, Protocol.DISCOVERY_PORT)) }
                    nextSend = System.currentTimeMillis() + 500
                }
                try {
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val host = p.address.hostAddress ?: continue
                    parseReply(host, p.data, p.length)?.let { found[it.fingerprintHex + "@" + host] = it }
                } catch (_: SocketTimeoutException) {
                    // keep listening until the scan ends
                }
            }
        }
        return found.values.toList()
    }
}
