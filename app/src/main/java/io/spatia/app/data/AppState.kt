package io.spatia.app.data

import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import kotlinx.serialization.Serializable

/** Everything the app persists. Stored only in app-private DataStore; never backed up or transmitted. */
@Serializable
data class AppState(
    val perType: Map<OutputType, SoundSettings> = emptyMap(),
    val perDevice: Map<String, SoundSettings> = emptyMap(),
    val rememberPerDevice: Boolean = true,
    val manualOutput: OutputType? = null,
    val customPreset: SoundSettings? = null,
    /** Per-app boost in dB for system-wide sessions, keyed by package name. Capped at use time. */
    val appBoostDb: Map<String, Float> = emptyMap(),
    val systemWideEnabled: Boolean = false,
    val bitPerfectUsb: Boolean = false,
    val doseTrackingEnabled: Boolean = false,
    val doseWeekStartEpochDay: Long = 0,
    val doseFraction: Double = 0.0,
    val volumeWarningAcknowledgedAt: Long = 0,
    val sleepTimerMinutes: Int = 0,
) {
    fun validated(): AppState = copy(
        perType = perType.mapValues { it.value.validated() },
        perDevice = perDevice.entries.take(MAX_DEVICES).associate { it.key.take(64) to it.value.validated() },
        customPreset = customPreset?.validated(),
        appBoostDb = appBoostDb.entries.filter { PACKAGE_RX.matches(it.key) }.take(MAX_APPS)
            .associate { it.key to it.value.let { v -> if (v.isFinite()) v.coerceIn(0f, 12f) else 0f } },
        doseFraction = if (doseFraction.isFinite()) doseFraction.coerceIn(0.0, 100.0) else 0.0,
        sleepTimerMinutes = sleepTimerMinutes.coerceIn(0, 240),
    )

    companion object {
        const val MAX_DEVICES = 32
        const val MAX_APPS = 64
        val PACKAGE_RX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    }
}
