package io.spatia.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.app.system.EffectState
import io.spatia.app.system.SessionInfo
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.Mode

/** What the home screen tells the user about the effects on all apps. Pure, so it can be unit-tested. */
enum class HomeStatus { OFF, STARTING, WORKING_ALL, TAKEN_OVER, REFUSED_ANNOUNCED_ONLY, REFUSED }

fun homeStatus(enabled: Boolean, globalMode: Boolean, sessions: List<SessionInfo>): HomeStatus {
    if (!enabled) return HomeStatus.OFF
    val global = sessions.firstOrNull { it.isGlobal }
    val announced = sessions.any { !it.isGlobal && it.eq == EffectState.ACTIVE }
    return when {
        global == null && !globalMode -> if (announced) HomeStatus.REFUSED_ANNOUNCED_ONLY else HomeStatus.REFUSED
        global == null -> HomeStatus.STARTING
        global.eq == EffectState.ACTIVE -> HomeStatus.WORKING_ALL
        global.eq == EffectState.TAKEN_OVER -> HomeStatus.TAKEN_OVER
        announced -> HomeStatus.REFUSED_ANNOUNCED_ONLY
        else -> HomeStatus.REFUSED
    }
}

/** Phone brands whose ROMs ship their own system-wide enhancer that can hold the global effect. */
enum class Brand { XIAOMI, OPPO_REALME, ONEPLUS, VIVO, SAMSUNG, OTHER }

fun brandOf(manufacturer: String): Brand = when (manufacturer.lowercase()) {
    "xiaomi", "redmi", "poco" -> Brand.XIAOMI
    "oppo", "realme" -> Brand.OPPO_REALME
    "oneplus" -> Brand.ONEPLUS
    "vivo", "iqoo" -> Brand.VIVO
    "samsung" -> Brand.SAMSUNG
    else -> Brand.OTHER
}

private val homeModes = listOf(
    Mode.MUSIC to R.string.mode_music, Mode.MOVIE to R.string.mode_movie, Mode.GAMING to R.string.mode_gaming,
    Mode.VOICE to R.string.mode_voice, Mode.NIGHT to R.string.mode_night,
)

@Composable
fun HomeScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val testPlaying by vm.testPlaying.collectAsStateWithLifecycle()
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.setEnabled(true) }
    val enabled = cfg.app.systemWideEnabled
    val status = homeStatus(enabled, cfg.app.globalMode, sessions)
    val global = sessions.firstOrNull { it.isGlobal }
    val s = cfg.settings

    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        // ---- main switch ----
        val good = status == HomeStatus.WORKING_ALL
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (enabled) R.string.home_on else R.string.home_off),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    val desc = stringResource(R.string.home_switch)
                    Switch(
                        checked = enabled,
                        modifier = Modifier.semantics { contentDescription = desc },
                        onCheckedChange = { v ->
                            if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.setEnabled(v)
                        },
                    )
                }
                val (headline, detail) = when (status) {
                    HomeStatus.OFF -> R.string.home_status_off to R.string.home_status_off_detail
                    HomeStatus.STARTING -> R.string.home_status_starting to R.string.home_status_starting_detail
                    HomeStatus.WORKING_ALL -> R.string.home_status_working to R.string.home_status_working_detail
                    HomeStatus.TAKEN_OVER -> R.string.home_status_taken to R.string.home_status_taken_detail
                    HomeStatus.REFUSED_ANNOUNCED_ONLY -> R.string.home_status_partial to R.string.home_status_partial_detail
                    HomeStatus.REFUSED -> R.string.home_status_refused to R.string.home_status_refused_detail
                }
                Text(stringResource(headline), style = MaterialTheme.typography.titleMedium,
                    color = when (status) {
                        HomeStatus.WORKING_ALL -> MaterialTheme.colorScheme.primary
                        HomeStatus.OFF, HomeStatus.STARTING -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.error
                    })
                Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- test ----
        Section(stringResource(R.string.test_title)) {
            Text(stringResource(R.string.test_help), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (testPlaying) vm.stopTest() else vm.playTest() }) {
                    Text(stringResource(if (testPlaying) R.string.test_stop else R.string.test_play))
                }
            }
            SwitchRow(stringResource(R.string.compare_bypass), s.bypass, enabled = enabled,
                supporting = stringResource(R.string.compare_bypass_help)) { v -> vm.edit { it.copy(bypass = v) } }
        }

        // ---- quick mode ----
        Section(stringResource(R.string.modes_title)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                homeModes.forEach { (m, label) ->
                    FilterChip(selected = s.mode == m, onClick = { vm.applyMode(m) }, label = { Text(stringResource(label)) })
                }
            }
            Text(stringResource(R.string.home_more_in_sound), style = MaterialTheme.typography.bodySmall)
        }

        // ---- what is running ----
        if (enabled) {
            Section(stringResource(R.string.home_effects_title)) {
                EffectRow(stringResource(R.string.home_fx_sound), global?.eq ?: EffectState.IDLE, idleText = R.string.fx_waiting)
                EffectRow(
                    stringResource(R.string.home_fx_environment),
                    global?.environment ?: EffectState.IDLE,
                    idleText = if (s.environment == Environment.OFF) R.string.fx_off else R.string.fx_waiting,
                )
                EffectRow(stringResource(R.string.home_fx_spatial), global?.spatial ?: EffectState.IDLE,
                    idleText = if (s.spatialEnabled) R.string.fx_waiting else R.string.fx_off)
                if (status != HomeStatus.WORKING_ALL) {
                    OutlinedButton(onClick = vm::retryEffects) { Text(stringResource(R.string.home_retry)) }
                }
            }
        }

        // ---- phone-specific help ----
        Section(stringResource(R.string.home_tips_title)) {
            val brand = brandOf(Build.MANUFACTURER ?: "")
            Text(stringResource(R.string.home_tip_general), style = MaterialTheme.typography.bodySmall)
            val tip = when (brand) {
                Brand.XIAOMI -> R.string.home_tip_xiaomi
                Brand.OPPO_REALME -> R.string.home_tip_oppo
                Brand.ONEPLUS -> R.string.home_tip_oneplus
                Brand.VIVO -> R.string.home_tip_vivo
                Brand.SAMSUNG -> R.string.home_tip_samsung
                Brand.OTHER -> null
            }
            tip?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.home_tip_battery), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::openAppSettings) { Text(stringResource(R.string.home_open_app_settings)) }
        }
    }
}

@Composable
private fun EffectRow(label: String, state: EffectState, idleText: Int) {
    val (text, color) = when (state) {
        EffectState.ACTIVE -> R.string.fx_active to MaterialTheme.colorScheme.primary
        EffectState.IDLE -> idleText to MaterialTheme.colorScheme.onSurfaceVariant
        EffectState.UNSUPPORTED -> R.string.fx_unsupported to MaterialTheme.colorScheme.error
        EffectState.REFUSED -> R.string.fx_refused to MaterialTheme.colorScheme.error
        EffectState.TAKEN_OVER -> R.string.fx_taken to MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(text), color = color, style = MaterialTheme.typography.bodyMedium)
    }
}
