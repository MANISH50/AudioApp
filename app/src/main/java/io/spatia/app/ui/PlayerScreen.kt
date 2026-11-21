package io.spatia.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R

@Composable
fun PlayerScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val player by vm.player.collectAsStateWithLifecycle()
    val meters by vm.meters.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val sleep by vm.sleepRemainingS.collectAsStateWithLifecycle()
    val sleepMinutes by vm.sleepMinutes.collectAsStateWithLifecycle()
    val samplePlaying by vm.samplePlaying.collectAsStateWithLifecycle()
    // Storage Access Framework: no storage permission needed, access is limited to the picked file (scoped storage).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::openFile) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(stringResource(R.string.player_title)) {
            Text(player.title ?: stringResource(R.string.player_nothing), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { picker.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream")) }) {
                    Text(stringResource(R.string.player_open))
                }
                androidx.compose.material3.OutlinedButton(onClick = { vm.playSample() }) {
                    Text(stringResource(R.string.sample_play))
                }
                FilledIconButton(onClick = vm::playPause, enabled = player.title != null) {
                    Icon(
                        if (player.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (player.isPlaying) R.string.player_pause else R.string.player_play),
                    )
                }
            }
            if (player.durationMs > 0) {
                val label = stringResource(R.string.player_position)
                Slider(
                    value = player.positionMs.toFloat() / player.durationMs,
                    onValueChange = { vm.seekTo((it * player.durationMs).toLong()) },
                    modifier = Modifier.semantics { contentDescription = label },
                )
                Text("${fmt(player.positionMs)} / ${fmt(player.durationMs)}", style = MaterialTheme.typography.bodySmall)
            }
            if (samplePlaying) {
                val sec = player.positionMs / 1000.0
                val now = when {
                    sec < io.spatia.dsp.sample.SampleTrack.VOICE_START_S -> R.string.sample_section_groove
                    sec < io.spatia.dsp.sample.SampleTrack.HITS_START_S -> R.string.sample_section_voice
                    sec < io.spatia.dsp.sample.SampleTrack.PAN_START_S -> R.string.sample_section_hits
                    else -> R.string.sample_section_sweep
                }
                Text(stringResource(R.string.sample_now, stringResource(now)), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.sample_guide), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(stringResource(R.string.player_formats), style = MaterialTheme.typography.bodySmall)
            }
        }

        player.error?.let { err ->
            OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.player_cannot_play), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(err))
                    TextButton(onClick = vm::clearError) { Text(stringResource(R.string.ok)) }
                }
            }
        }

        Section(stringResource(R.string.compare_title)) {
            SwitchRow(stringResource(R.string.compare_bypass), cfg.settings.bypass,
                supporting = stringResource(R.string.compare_bypass_help)) { v -> vm.edit { it.copy(bypass = v) } }
        }

        Section(stringResource(R.string.meters_title)) {
            MeterBar(stringResource(R.string.meter_peak), meters.peakDb)
            MeterBar(stringResource(R.string.meter_rms), meters.rmsDb)
            Spectrum(meters.spectrum)
        }

        Section(stringResource(R.string.sleep_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (min in listOf(0, 15, 30, 60)) {
                    FilterChip(
                        selected = sleepMinutes == min,
                        onClick = { vm.setSleepTimer(min) },
                        label = { Text(if (min == 0) stringResource(R.string.sleep_off) else stringResource(R.string.sleep_minutes, min)) },
                    )
                }
            }
            if (sleep > 0) Text(stringResource(R.string.sleep_remaining, fmt(sleep * 1000L)))
        }
    }
}

@Composable
private fun MeterBar(label: String, db: Float) {
    val fraction = ((db + 60f) / 60f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "$label ${"%.0f".format(db)} dB" }) {
        Text("$label  ${if (db <= -120f) "-∞" else "%.1f".format(db)} dBFS", style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
    }
}

@Composable
private fun Spectrum(bands: FloatArray) {
    val color = MaterialTheme.colorScheme.primary
    val desc = stringResource(R.string.meter_spectrum)
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(top = 8.dp).semantics { contentDescription = desc }) {
        if (bands.isEmpty()) return@Canvas
        val w = size.width / bands.size
        bands.forEachIndexed { i, db ->
            val h = ((db + 80f) / 80f).coerceIn(0f, 1f) * size.height
            drawRect(color, topLeft = Offset(i * w + 1f, size.height - h), size = Size(w - 2f, h))
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
