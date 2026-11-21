package io.spatia.app.audio

import io.spatia.app.data.AppState
import io.spatia.app.data.SettingsRepository
import io.spatia.app.output.OutputMonitor
import io.spatia.app.output.OutputState
import io.spatia.app.safety.VolumeGuard
import io.spatia.app.safety.VolumeState
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.ProfileSelector
import io.spatia.dsp.model.SoundSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The resolved configuration the system-wide effects should use right now. */
data class EffectiveConfig(
    val output: OutputType,
    val deviceKey: String?,
    val settings: SoundSettings,
    val volume: VolumeState,
    val app: AppState,
)

/** Single source of truth: stored settings + detected output + volume resolved into an [EffectiveConfig]. */
class AudioEngine(
    scope: CoroutineScope,
    private val repo: SettingsRepository,
    outputs: OutputMonitor,
    volume: VolumeGuard,
) {
    val config: StateFlow<EffectiveConfig> = combine(repo.state, outputs.state, volume.state) { app, out, vol ->
        resolve(app, out, vol)
    }.stateIn(scope, SharingStarted.Eagerly, resolve(AppState(), OutputState(), VolumeState()))

    private fun resolve(app: AppState, out: OutputState, vol: VolumeState): EffectiveConfig {
        val output = ProfileSelector.activeOutput(out.connected.map { it.type }, app.manualOutput)
        val device = out.connected.firstOrNull { it.type == output }
        val key = if (app.rememberPerDevice && output != OutputType.SPEAKER) device?.key else null
        val settings = ProfileSelector.settingsFor(output, key, app.perDevice, app.perType)
        return EffectiveConfig(output, key, settings, vol, app)
    }

    /** Edit the settings for whatever output is active right now (per-device if remembered, else per-type). */
    fun editActive(transform: (SoundSettings) -> SoundSettings) {
        val cfg = config.value
        repo.update { app ->
            val next = transform(cfg.settings).validated()
            val withCustom = if (next.mode == Mode.CUSTOM) app.copy(customPreset = next) else app
            if (cfg.deviceKey != null) withCustom.copy(perDevice = withCustom.perDevice + (cfg.deviceKey to next))
            else withCustom.copy(perType = withCustom.perType + (cfg.output to next))
        }
    }

    /** Small tweaks made on top of a preset turn it into Custom so the preset itself stays intact. */
    fun tweak(transform: (SoundSettings) -> SoundSettings) = editActive { transform(it).copy(mode = Mode.CUSTOM) }

    fun applyMode(mode: Mode) = editActive { current ->
        if (mode == Mode.CUSTOM) (config.value.app.customPreset ?: current).copy(mode = Mode.CUSTOM)
        else Presets.forMode(mode, current)
    }
}
