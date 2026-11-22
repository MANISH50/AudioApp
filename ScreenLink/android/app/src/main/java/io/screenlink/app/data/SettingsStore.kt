package io.screenlink.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settings by preferencesDataStore(name = "settings")

/** App settings in app-private storage (excluded from backups). */
class SettingsStore(private val context: Context) {
    private val allowControlKey = booleanPreferencesKey("allow_laptop_control")

    /** "Let the laptop control this phone". Off until the user turns it on. */
    val allowControl: Flow<Boolean> = context.settings.data.map { it[allowControlKey] ?: false }

    suspend fun setAllowControl(value: Boolean) {
        context.settings.edit { it[allowControlKey] = value }
    }
}
