package io.spatia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.app.ui.hearing.HearingTestDialog
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings

private val modeLabels = mapOf(
    Mode.MUSIC to R.string.mode_music, Mode.MOVIE to R.string.mode_movie, Mode.GAMING to R.string.mode_gaming,
    Mode.VOICE to R.string.mode_voice, Mode.NIGHT to R.string.mode_night, Mode.CUSTOM to R.string.mode_custom,
)

@Composable
fun SoundScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val headAvail by vm.headTrackerAvailable.collectAsStateWithLifecycle()
    val s = cfg.settings
    val headphones = OutputSafety.forType(cfg.output).isHeadphones
    var hearingOpen by remember { mutableStateOf(false) }
    val corrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importCorrection) }
    val irPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importImpulse) }

    val streams by vm.activeMediaStreams.collectAsStateWithLifecycle()
    val ownPlaying by vm.ownPlaying.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val otherAppUnprocessed = streams - (if (ownPlaying) 1 else 0) > 0 &&
        sessions.none { it.kind != io.spatia.app.system.SessionEffectKind.FAILED }

    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (otherAppUnprocessed) {
            androidx.compose.material3.OutlinedCard(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.banner_other_app_title), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.banner_other_app_text), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.TextButton(onClick = { vm.requestedTab.value = 3 }) {
                        Text(stringResource(R.string.banner_open_apps))
                    }
                }
            }
        }
        Section(stringResource(R.string.modes_title)) {
            Text(stringResource(R.string.modes_for_output, stringResource(outputLabel(cfg.output))), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(selected = s.mode == m, onClick = { vm.applyMode(m) }, label = { Text(stringResource(modeLabels.getValue(m))) })
                }
            }
        }

        EnvironmentSection(
            environment = s.environment,
            intensity = s.environmentIntensity,
            onEnvironment = { e -> vm.edit { it.copy(environment = e) } },
            onIntensity = { v -> vm.edit { it.copy(environmentIntensity = v) } },
            onTrySample = { vm.playSample() },
        )

        Section(stringResource(R.string.spatial_title)) {
            SwitchRow(stringResource(R.string.spatial_enable), s.spatialEnabled,
                supporting = stringResource(R.string.spatial_help)) { v -> vm.tweak { it.copy(spatialEnabled = v) } }
            LabeledSlider(stringResource(R.string.spatial_width), s.width, 0f..2f, ::pctText) { v -> vm.tweak { it.copy(width = v) } }
            LabeledSlider(stringResource(R.string.spatial_room), s.roomSize, 0f..1f, ::pctText, enabled = s.spatialEnabled) { v ->
                vm.tweak { it.copy(roomSize = v) }
            }
            SwitchRow(stringResource(R.string.spatial_upmix), s.surroundUpmix, enabled = s.spatialEnabled,
                supporting = stringResource(R.string.spatial_upmix_help)) { v -> vm.tweak { it.copy(surroundUpmix = v) } }
            SwitchRow(
                stringResource(R.string.spatial_head_tracking), s.headTracking, enabled = s.spatialEnabled && headphones,
                supporting = stringResource(
                    when {
                        android.os.Build.VERSION.SDK_INT < 33 -> R.string.head_tracking_needs_13
                        !headphones -> R.string.head_tracking_needs_headphones
                        s.headTracking && !headAvail -> R.string.head_tracking_no_sensor
                        else -> R.string.head_tracking_help
                    },
                ),
            ) { v -> vm.tweak { it.copy(headTracking = v) } }
            if (s.headTracking && headAvail) OutlinedButton(onClick = vm::recenterHead) { Text(stringResource(R.string.head_recenter)) }
        }

        Section(stringResource(R.string.eq_title)) {
            SoundSettings.EQ_BANDS.forEachIndexed { i, f ->
                val label = if (f >= 1000) "${(f / 1000).toInt()} kHz" else "${f.toInt()} Hz"
                LabeledSlider(stringResource(R.string.eq_band, label), s.eqGainsDb[i], -12f..12f, ::dbText) { v ->
                    vm.tweak { it.copy(eqGainsDb = it.eqGainsDb.toMutableList().also { l -> l[i] = v }) }
                }
            }
            OutlinedButton(onClick = { vm.tweak { it.copy(eqGainsDb = List(10) { 0f }) } }) { Text(stringResource(R.string.eq_reset)) }
        }

        Section(stringResource(R.string.tone_title)) {
            LabeledSlider(stringResource(R.string.tone_bass), s.bassDb, -12f..12f, ::dbText) { v -> vm.tweak { it.copy(bassDb = v) } }
            LabeledSlider(stringResource(R.string.tone_treble), s.trebleDb, -12f..12f, ::dbText) { v -> vm.tweak { it.copy(trebleDb = v) } }
            LabeledSlider(stringResource(R.string.tone_preamp), s.preampDb, -24f..6f, ::dbText) { v -> vm.tweak { it.copy(preampDb = v) } }
            LabeledSlider(stringResource(R.string.tone_voice), s.voiceClarity, 0f..1f, ::pctText) { v -> vm.tweak { it.copy(voiceClarity = v) } }
            SwitchRow(stringResource(R.string.tone_normalize), s.loudnessNormalization,
                supporting = stringResource(R.string.tone_normalize_help)) { v -> vm.tweak { it.copy(loudnessNormalization = v) } }
            SwitchRow(stringResource(R.string.tone_night), s.nightCompression,
                supporting = stringResource(R.string.tone_night_help)) { v -> vm.tweak { it.copy(nightCompression = v) } }
            SwitchRow(stringResource(R.string.tone_loudness_comp), s.loudnessCompensation,
                supporting = stringResource(R.string.tone_loudness_comp_help)) { v -> vm.tweak { it.copy(loudnessCompensation = v) } }
        }

        Section(stringResource(R.string.extras_title)) {
            LabeledSlider(stringResource(R.string.extras_crossfeed), s.crossfeed, 0f..1f, ::pctText,
                enabled = headphones && !s.spatialEnabled) { v -> vm.tweak { it.copy(crossfeed = v) } }
            Text(stringResource(R.string.extras_crossfeed_help), style = MaterialTheme.typography.bodySmall)
            LabeledSlider(stringResource(R.string.extras_psycho_bass), s.psychoBass, 0f..1f, ::pctText,
                enabled = cfg.output == OutputType.SPEAKER) { v -> vm.tweak { it.copy(psychoBass = v) } }
            Text(stringResource(R.string.extras_psycho_bass_help), style = MaterialTheme.typography.bodySmall)
        }

        Section(stringResource(R.string.correction_title)) {
            Text(stringResource(R.string.correction_help), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.correction_enable), s.correctionEnabled, enabled = s.correction.isNotEmpty(),
                supporting = if (s.correction.isEmpty()) stringResource(R.string.correction_none)
                else pluralStringResource(R.plurals.correction_loaded, s.correction.size, s.correction.size, s.correctionPreampDb)) { v ->
                vm.edit { it.copy(correctionEnabled = v) }
            }
            OutlinedButton(onClick = { corrPicker.launch(arrayOf("text/plain", "application/octet-stream")) }) {
                Text(stringResource(R.string.correction_import))
            }
        }

        Section(stringResource(R.string.ir_title)) {
            Text(stringResource(R.string.ir_help), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.ir_enable), s.convolutionEnabled && vm.hasImpulse, enabled = vm.hasImpulse) { v ->
                vm.edit { it.copy(convolutionEnabled = v) }
            }
            LabeledSlider(stringResource(R.string.ir_mix), s.convolutionMix, 0f..1f, ::pctText, enabled = vm.hasImpulse) { v ->
                vm.edit { it.copy(convolutionMix = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { irPicker.launch(arrayOf("audio/wav", "audio/x-wav", "audio/*")) }) { Text(stringResource(R.string.ir_import)) }
                if (vm.hasImpulse) OutlinedButton(onClick = vm::clearImpulse) { Text(stringResource(R.string.ir_clear)) }
            }
        }

        Section(stringResource(R.string.hearing_title)) {
            Text(stringResource(R.string.hearing_help), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.hearing_enable), s.hearingEnabled, enabled = s.hearingCompensation.isNotEmpty()) { v ->
                vm.edit { it.copy(hearingEnabled = v) }
            }
            OutlinedButton(onClick = { hearingOpen = true }, enabled = headphones) {
                Text(stringResource(if (headphones) R.string.hearing_start else R.string.hearing_needs_headphones))
            }
        }

        Section(stringResource(R.string.access_title)) {
            SwitchRow(stringResource(R.string.access_mono), s.mono) { v -> vm.edit { it.copy(mono = v) } }
            LabeledSlider(stringResource(R.string.access_balance), s.balance, -1f..1f, { v ->
                when { v < -0.01f -> "L ${pctText(-v)}"; v > 0.01f -> "R ${pctText(v)}"; else -> "C" }
            }) { v -> vm.edit { it.copy(balance = v) } }
        }
    }

    if (hearingOpen) HearingTestDialog(onDismiss = { hearingOpen = false }, onResult = { l, r -> vm.saveHearingResult(l, r); hearingOpen = false })
}

fun outputLabel(t: OutputType): Int = when (t) {
    OutputType.SPEAKER -> R.string.output_speaker
    OutputType.WIRED -> R.string.output_wired
    OutputType.BLUETOOTH -> R.string.output_bluetooth
    OutputType.USB -> R.string.output_usb
    OutputType.HDMI_CAST -> R.string.output_hdmi
}
