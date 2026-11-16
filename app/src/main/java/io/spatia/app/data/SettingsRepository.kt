package io.spatia.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "spatia")

class SettingsRepository(private val context: Context, private val scope: CoroutineScope) {
    private val key = stringPreferencesKey("state_v1")

    val state: StateFlow<AppState> = context.dataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> decode(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, AppState())

    fun update(transform: (AppState) -> AppState) {
        scope.launch {
            context.dataStore.edit { prefs ->
                val next = transform(decode(prefs[key])).validated()
                prefs[key] = JSON.encodeToString(AppState.serializer(), next)
            }
        }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        private const val MAX_JSON = 512 * 1024

        /** Corrupt or oversized data falls back to defaults rather than crashing the app. */
        fun decode(raw: String?): AppState {
            if (raw.isNullOrEmpty() || raw.length > MAX_JSON) return AppState()
            return try {
                JSON.decodeFromString(AppState.serializer(), raw).validated()
            } catch (e: Exception) {
                Log.w("Spatia", "Discarding unreadable settings")
                AppState()
            }
        }
    }
}
