package io.screenlink.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** A laptop this phone has paired with. Only public information: its certificate fingerprint, address and name. */
data class PairedLaptop(val fingerprintHex: String, val host: String, val port: Int, val name: String)

private val Context.store by preferencesDataStore(name = "pairings")

/** Paired laptops, in app-private storage (excluded from backups and device transfer). */
class PairingStore(private val context: Context) {
    private val key = stringPreferencesKey("laptops_v1")

    val laptops: Flow<List<PairedLaptop>> = context.store.data.map { decode(it[key].orEmpty()) }

    suspend fun all(): List<PairedLaptop> = laptops.first()

    suspend fun save(laptop: PairedLaptop) {
        context.store.edit { prefs ->
            val list = decode(prefs[key].orEmpty()).filterNot { it.fingerprintHex == laptop.fingerprintHex } + laptop
            prefs[key] = encode(list)
        }
    }

    suspend fun remove(fingerprintHex: String) {
        context.store.edit { prefs ->
            prefs[key] = encode(decode(prefs[key].orEmpty()).filterNot { it.fingerprintHex == fingerprintHex })
        }
    }

    suspend fun clear() {
        context.store.edit { it.remove(key) }
    }

    companion object {
        private fun clean(s: String) = s.filter { it != '\t' && it != '\n' && !it.isISOControl() }

        fun encode(list: List<PairedLaptop>): String =
            list.joinToString("\n") { "${it.fingerprintHex}\t${clean(it.host)}\t${it.port}\t${clean(it.name)}" }

        fun decode(s: String): List<PairedLaptop> = s.lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size != 4 || p[0].length != 64) return@mapNotNull null
            val port = p[2].toIntOrNull() ?: return@mapNotNull null
            PairedLaptop(p[0], p[1], port, p[3])
        }
    }
}
