package io.spatia.app.safety

import android.content.Context
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.spatia.app.output.OutputMonitor
import io.spatia.dsp.SafetyPolicy
import io.spatia.dsp.model.OutputSafety
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VolumeState(
    val fraction: Float = 0f,
    /** How far below maximum the media stream is, in dB (>= 0). Drives equal-loudness compensation. */
    val attenuationDb: Float = 0f,
    val aboveSafeLevel: Boolean = false,
)

/**
 * Watches the media volume and flags levels above the safe threshold for the current output. Headphone outputs use a
 * lower threshold than the speaker (see [OutputSafety]).
 */
class VolumeGuard(context: Context, private val outputs: OutputMonitor) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val resolver = context.contentResolver
    private val _state = MutableStateFlow(VolumeState())
    val state: StateFlow<VolumeState> = _state

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    fun start() {
        resolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        refresh()
    }

    fun refresh() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val fraction = idx.toFloat() / max
        val out = outputs.state.value
        val deviceType = out.detectedDevice?.platformType ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val attenuation = try {
            -am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, idx, deviceType)
        } catch (e: IllegalArgumentException) {
            (1f - fraction) * 60f
        }.let { if (it.isFinite()) it.coerceIn(0f, 90f) else (1f - fraction) * 60f }
        val safety = OutputSafety.forType(out.detected)
        _state.value = VolumeState(fraction, attenuation, SafetyPolicy.shouldWarnVolume(fraction, safety))
    }

    /** Lower the media volume to the safe threshold (offered from the warning dialog). */
    fun reduceToSafe() {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val safety = OutputSafety.forType(outputs.state.value.detected)
        val target = (max * safety.volumeWarningFraction).toInt().coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        refresh()
    }

    companion object {
        /** EU EN 50332-3 style: re-ask after 20 hours of acknowledged high-volume use. */
        const val REACKNOWLEDGE_MS = 20L * 60 * 60 * 1000
    }
}
