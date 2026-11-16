package io.spatia.app.ui.hearing

import android.media.AudioTrack
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.spatia.app.R
import io.spatia.dsp.model.SoundSettings

/**
 * Simple ascending-level listening check: for each ear and frequency, the tone starts very quiet and rises in 5 dB
 * steps until the user hears it. Results are relative, stored only on the device, and not a medical test.
 */
@Composable
fun HearingTestDialog(onDismiss: () -> Unit, onResult: (List<Float>, List<Float>) -> Unit) {
    val freqs = SoundSettings.HEARING_FREQS
    var started by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) } // 0 until 2 * freqs.size
    var level by remember { mutableFloatStateOf(START_DB) }
    val left = remember { mutableStateListOf<Float>() }
    val right = remember { mutableStateListOf<Float>() }
    var track by remember { mutableStateOf<AudioTrack?>(null) }
    DisposableEffect(Unit) { onDispose { track?.release() } }

    val leftEar = step < freqs.size
    val freq = freqs[step % freqs.size]
    fun play() { track?.release(); track = TonePlayer.play(freq, level, leftEar) }
    fun record(v: Float) {
        if (leftEar) left += v else right += v
        level = START_DB
        if (step + 1 >= freqs.size * 2) { track?.release(); onResult(left.toList(), right.toList()) } else { step++; play() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hearing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!started) {
                    Text(stringResource(R.string.hearing_instructions))
                } else {
                    LinearProgressIndicator(progress = { step / (freqs.size * 2f) })
                    Text(stringResource(if (leftEar) R.string.hearing_left else R.string.hearing_right, freq.toInt()))
                    Text(stringResource(R.string.hearing_level, level.toInt()))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { record(level) }) { Text(stringResource(R.string.hearing_heard)) }
                        OutlinedButton(onClick = {
                            if (level >= MAX_DB) record(MAX_DB) else { level += 5f; play() }
                        }) { Text(stringResource(R.string.hearing_not_heard)) }
                    }
                    TextButton(onClick = ::play) { Text(stringResource(R.string.hearing_replay)) }
                }
            }
        },
        confirmButton = {
            if (!started) TextButton(onClick = { started = true; play() }) { Text(stringResource(R.string.hearing_begin)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val START_DB = -75f
private const val MAX_DB = -20f
