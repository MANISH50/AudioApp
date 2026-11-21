package io.spatia.app.audio

import io.spatia.app.data.AppState
import io.spatia.app.data.SettingsRepository
import io.spatia.app.output.OutputMonitor
import io.spatia.app.player.ChainHost
import io.spatia.app.output.OutputState
import io.spatia.app.safety.VolumeGuard
import io.spatia.app.safety.VolumeState
import io.spatia.app.spatial.HeadTrackerSource
import io.spatia.dsp.DspChain
import io.spatia.dsp.analysis.Meters
import io.spatia.dsp.filters.Convolver
import io.spatia.dsp.io.WavReader
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.ProfileSelector
import io.spatia.dsp.model.SoundSettings
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** The resolved configuration every processing path (player + system-wide sessions) should use right now. */
data class EffectiveConfig(
    val output: OutputType,
    val deviceKey: String?,
    val settings: SoundSettings,
    val volume: VolumeState,
    val app: AppState,
)

/**
 * Single source of truth that resolves stored settings + detected output + volume into an [EffectiveConfig] and
 * pushes it to every live [DspChain].
 */
class AudioEngine(
    private val scope: CoroutineScope,
    private val repo: SettingsRepository,
    private val outputs: OutputMonitor,
    private val volume: VolumeGuard,
    private val headTracker: HeadTrackerSource,
) : ChainHost {
    private val chains = CopyOnWriteArraySet<DspChain>()
    private val impulse = MutableStateFlow<WavReader.Wav?>(null)
    val hasImpulse: Boolean get() = impulse.value != null
    @Volatile override var meters: Meters? = null
    override val bitPerfect: Boolean get() = config.value.app.bitPerfectUsb
    /** True while Spatia's own player is playing (set by PlaybackService). */
    val ownPlaying = MutableStateFlow(false)
    val replayGainDb = MutableStateFlow(0f)

    val config: StateFlow<EffectiveConfig> = combine(repo.state, outputs.state, volume.state) { app, out, vol ->
        resolve(app, out, vol)
    }.stateIn(scope, SharingStarted.Eagerly, resolve(AppState(), OutputState(), VolumeState()))

    fun start() {
        config.onEach { cfg ->
            chains.forEach { push(it, cfg) }
            if (cfg.settings.spatialEnabled && cfg.settings.headTracking) headTracker.start() else headTracker.stop()
        }.launchIn(scope)
        headTracker.onYaw = { yaw -> chains.forEach { it.setHeadYaw(yaw) } }
        impulse.onEach { ir -> chains.forEach { attachConvolver(it, ir) } }.launchIn(scope)
        replayGainDb.onEach { chains.forEach { c -> push(c, config.value) } }.launchIn(scope)
    }

    private fun resolve(app: AppState, out: OutputState, vol: VolumeState): EffectiveConfig {
        val output = ProfileSelector.activeOutput(out.connected.map { it.type }, app.manualOutput)
        val device = out.connected.firstOrNull { it.type == output }
        val key = if (app.rememberPerDevice && output != OutputType.SPEAKER) device?.key else null
        val settings = ProfileSelector.settingsFor(output, key, app.perDevice, app.perType)
        return EffectiveConfig(output, key, settings, vol, app)
    }

    // ---- chain registration (called from the audio thread's processor) ----
    override fun register(chain: DspChain) {
        chains += chain
        push(chain, config.value)
        attachConvolver(chain, impulse.value)
    }

    override fun unregister(chain: DspChain) { chains -= chain }

    private fun push(chain: DspChain, cfg: EffectiveConfig) {
        val s = cfg.settings.copy(preampDb = (cfg.settings.preampDb + replayGainDb.value).coerceIn(-24f, 6f))
        chain.setSettings(s, cfg.output, cfg.volume.attenuationDb)
    }

    private fun attachConvolver(chain: DspChain, wav: WavReader.Wav?) {
        chain.setConvolver(wav?.let { Convolver(WavReader.prepareImpulse(it, chain.sampleRate)) })
    }

    /** Impulse responses live in memory only (re-import after restart); nothing is copied out of the user's file. */
    fun setImpulseResponse(wav: WavReader.Wav?) { impulse.value = wav }

    // ---- editing ----
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

    fun recenterHead() = headTracker.recenter()
}
