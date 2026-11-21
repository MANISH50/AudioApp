package io.spatia.app.system

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.util.Log
import io.spatia.app.audio.AudioEngine
import io.spatia.app.audio.EffectiveConfig
import io.spatia.app.data.AppState
import io.spatia.dsp.SafetyPolicy
import io.spatia.dsp.filters.FilterSpec
import io.spatia.dsp.filters.ToneCurves
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.I3dl2Params
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.toI3dl2
import io.spatia.dsp.model.SoundSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

enum class SessionEffectKind { DYNAMICS_PROCESSING, EQUALIZER_ONLY, FAILED }

data class SessionInfo(
    val sessionId: Int,
    val packageName: String,
    val kind: SessionEffectKind,
    /** True when an environment (Hall, Cinema...) is currently applied to this session via the platform reverb. */
    val environmentActive: Boolean = false,
    val isGlobal: Boolean = false,
)

/** Target values for one session's effect, computed from the active settings. Pure data for ramping/tests. */
data class SessionTargets(val bandGainsDb: FloatArray, val inputGainDb: Float, val limiterDb: Float, val night: Boolean) {
    override fun equals(other: Any?) = other is SessionTargets && bandGainsDb.contentEquals(other.bandGainsDb) &&
        inputGainDb == other.inputGainDb && limiterDb == other.limiterDb && night == other.night
    override fun hashCode() = bandGainsDb.contentHashCode() * 31 + inputGainDb.hashCode()
}

/**
 * Enhancement for other apps through the documented AudioEffect API:
 *  - sessions that their owning app announces (ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION or the effect-panel intent);
 *  - optionally the global output mix (session 0, "Global mode"). That route is deprecated by Android and works on
 *    many but not all devices, so it is opt-in.
 * No PCM access is possible here, so Spatia's own DSP can't run. Instead: DynamicsProcessing (EQ, tone, voice,
 * loudness compensation, correction, night, limiter, per-app boost) plus the platform EnvironmentalReverb as an
 * insert effect for environments (dry signal kept, room added on top).
 */
class SessionEffectsManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: AudioEngine,
) {
    private class Attached(
        var info: SessionInfo,
        val dp: DynamicsProcessing?,
        val eq: Equalizer?,
        var current: SessionTargets?,
        var rev: EnvironmentalReverb? = null,
        var revEnv: Environment = Environment.OFF,
        var revLevel: Int = I3dl2Params.SILENT_MB,
    )

    private val attached = LinkedHashMap<Int, Attached>()
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private var rampJob: Job? = null
    private var observing = false

    val dynamicsProcessingSupported: Boolean by lazy { effectSupported(AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING) }
    val equalizerSupported: Boolean by lazy { effectSupported(AudioEffect.EFFECT_TYPE_EQUALIZER) }
    val environmentalReverbSupported: Boolean by lazy { effectSupported(AudioEffect.EFFECT_TYPE_ENV_REVERB) }

    private fun effectSupported(type: java.util.UUID) = try {
        AudioEffect.queryEffects()?.any { it.type == type } == true
    } catch (e: RuntimeException) { false }

    fun startObserving() {
        if (observing) return
        observing = true
        engine.config.onEach { cfg ->
            syncGlobal(cfg.app.globalMode && cfg.app.systemWideEnabled)
            applyAll(cfg)
        }.launchIn(scope)
    }

    /** A session announced by another app (validated by the caller and again here). */
    fun open(sessionId: Int, packageName: String) {
        if (sessionId <= 0) return
        val pkg = packageName.take(255).takeIf { AppState.PACKAGE_RX.matches(it) } ?: "unknown"
        if (pkg == context.packageName) return // our own player is processed in-app
        attach(sessionId, pkg, global = false)
    }

    private fun syncGlobal(enabled: Boolean) {
        if (enabled && !attached.containsKey(GLOBAL_SESSION)) attach(GLOBAL_SESSION, "global", global = true)
        else if (!enabled && attached.containsKey(GLOBAL_SESSION)) close(GLOBAL_SESSION)
    }

    private fun attach(sessionId: Int, pkg: String, global: Boolean) {
        if (attached.containsKey(sessionId) || attached.size >= MAX_SESSIONS) return
        val a = try {
            if (dynamicsProcessingSupported) {
                Attached(SessionInfo(sessionId, pkg, SessionEffectKind.DYNAMICS_PROCESSING, isGlobal = global),
                    DynamicsProcessing(0, sessionId, buildConfig()).apply { enabled = true }, null, null)
            } else if (equalizerSupported) {
                Attached(SessionInfo(sessionId, pkg, SessionEffectKind.EQUALIZER_ONLY, isGlobal = global), null,
                    Equalizer(0, sessionId).apply { enabled = true }, null)
            } else null
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not attach effect to a session")
            null
        } ?: Attached(SessionInfo(sessionId, pkg, SessionEffectKind.FAILED, isGlobal = global), null, null, null)
        attached[sessionId] = a
        publish()
        applyAll(engine.config.value)
    }

    fun close(sessionId: Int) {
        attached.remove(sessionId)?.let { releaseQuietly(it) }
        publish()
    }

    fun closeAll() {
        rampJob?.cancel()
        attached.values.forEach(::releaseQuietly)
        attached.clear()
        publish()
    }

    private fun releaseQuietly(a: Attached) {
        try { a.dp?.release(); a.eq?.release(); a.rev?.release() } catch (_: RuntimeException) { }
        a.rev = null
    }

    private fun publish() {
        _sessions.value = attached.values.map { it.info.copy(environmentActive = it.rev != null && it.revEnv != Environment.OFF) }
    }

    private fun buildConfig(): DynamicsProcessing.Config {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            true, BAND_CENTERS.size, true, 1, false, 0, true,
        ).build()
        for (ch in 0 until 2) {
            val pre = cfg.getPreEqByChannelIndex(ch)
            BAND_EDGES.forEachIndexed { i, edge -> pre.setBand(i, DynamicsProcessing.EqBand(true, edge, 0f)) }
            cfg.setPreEqByChannelIndex(ch, pre)
            // Night compression band (bypassed via ratio 1 when off).
            cfg.setMbcBandByChannelIndex(ch, 0, DynamicsProcessing.MbcBand(true, 20000f, 5f, 200f, 1f, -32f, 6f, -90f, 1f, 0f, 0f))
            cfg.setLimiterByChannelIndex(ch, DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        }
        return cfg
    }

    private fun applyAll(cfg: EffectiveConfig) {
        rampJob?.cancel()
        if (attached.isEmpty()) return
        val safety = OutputSafety.forType(cfg.output)
        val env = cfg.settings.environment
        val roomTarget = env.preset?.toI3dl2(cfg.settings.environmentIntensity)
        rampJob = scope.launch {
            val targets = attached.mapValues { (_, a) ->
                val boost = if (a.info.isGlobal) 0f else cfg.app.appBoostDb[a.info.packageName] ?: 0f
                computeTargets(cfg.settings, safety, cfg.volume.attenuationDb, boost, hasLimiter = a.dp != null)
            }
            // Environment: (re)create the platform reverb when the space changes, starting silent.
            for (a in attached.values) prepareReverb(a, env, roomTarget)
            publish()
            val roomFrom = attached.mapValues { it.value.revLevel }
            val roomTo = roomTarget?.roomLevel ?: I3dl2Params.SILENT_MB
            // Smooth parameter ramps: ~200 ms in 8 steps so changes never click.
            for (step in 1..RAMP_STEPS) {
                val t = step.toFloat() / RAMP_STEPS
                for ((id, a) in attached) {
                    val target = targets[id] ?: continue
                    val from = a.current ?: target
                    apply(a, interpolate(from, target, t))
                    val r0 = roomFrom[id] ?: I3dl2Params.SILENT_MB
                    setRoomLevel(a, (r0 + (roomTo - r0) * t).toInt())
                }
                delay(RAMP_MS / RAMP_STEPS)
            }
            for ((id, a) in attached) {
                a.current = targets[id]
                if (roomTarget == null && a.rev != null) {
                    try { a.rev?.release() } catch (_: RuntimeException) { }
                    a.rev = null; a.revEnv = Environment.OFF; a.revLevel = I3dl2Params.SILENT_MB
                }
            }
            publish()
        }
    }

    private fun prepareReverb(a: Attached, env: Environment, p: I3dl2Params?) {
        if (p == null || !environmentalReverbSupported || a.info.kind == SessionEffectKind.FAILED) return
        try {
            if (a.rev == null || a.revEnv != env) {
                a.rev?.release()
                a.rev = EnvironmentalReverb(0, a.info.sessionId)
                a.revLevel = I3dl2Params.SILENT_MB
                a.revEnv = env
            }
            val r = a.rev ?: return
            r.roomLevel = a.revLevel.toShort()
            r.roomHFLevel = p.roomHFLevel.toShort()
            r.decayTime = p.decayTime
            r.decayHFRatio = p.decayHFRatio.toShort()
            r.reflectionsLevel = p.reflectionsLevel.toShort()
            r.reflectionsDelay = p.reflectionsDelay
            r.reverbLevel = p.reverbLevel.toShort()
            r.reverbDelay = p.reverbDelay
            r.diffusion = p.diffusion.toShort()
            r.density = p.density.toShort()
            r.enabled = true
        } catch (e: RuntimeException) {
            // Some devices only offer the reverb as an auxiliary (send) effect, which other apps' players never use.
            Log.w(TAG, "Environment reverb not available for this session")
            try { a.rev?.release() } catch (_: RuntimeException) { }
            a.rev = null; a.revEnv = Environment.OFF
        }
    }

    private fun setRoomLevel(a: Attached, mb: Int) {
        val r = a.rev ?: return
        try {
            r.roomLevel = mb.coerceIn(I3dl2Params.SILENT_MB, 0).toShort()
            a.revLevel = mb
        } catch (_: RuntimeException) { }
    }

    private fun apply(a: Attached, t: SessionTargets) {
        try {
            a.dp?.let { dp ->
                for (ch in 0 until 2) {
                    t.bandGainsDb.forEachIndexed { i, g -> dp.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, BAND_EDGES[i], g)) }
                    dp.setInputGainbyChannel(ch, t.inputGainDb)
                    dp.setMbcBandByChannelIndex(ch, 0, DynamicsProcessing.MbcBand(true, 20000f, 5f, 200f,
                        if (t.night) 4f else 1f, -32f, 6f, -90f, 1f, 0f, if (t.night) 6f else 0f))
                    dp.setLimiterByChannelIndex(ch, DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, t.limiterDb, 0f))
                }
            }
            a.eq?.let { eq ->
                val range = eq.bandLevelRange
                for (b in 0 until eq.numberOfBands) {
                    val centreHz = eq.getCenterFreq(b.toShort()) / 1000f
                    val idx = BAND_CENTERS.indices.minBy { kotlin.math.abs(kotlin.math.ln(BAND_CENTERS[it] / centreHz)) }
                    val mb = ((t.bandGainsDb[idx] + t.inputGainDb) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(b.toShort(), mb.toShort())
                }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Effect update failed; the session may have ended")
        }
    }

    companion object {
        private const val TAG = "Spatia"
        /** The global output mix. Deprecated by Android for insert effects; used only in opt-in Global mode. */
        const val GLOBAL_SESSION = 0
        const val MAX_SESSIONS = 16
        private const val RAMP_STEPS = 8
        private const val RAMP_MS = 200L
        val BAND_CENTERS = SoundSettings.EQ_BANDS
        /** DynamicsProcessing bands are defined by their upper cutoff: geometric midpoints between centres. */
        val BAND_EDGES = FloatArray(BAND_CENTERS.size) { i ->
            if (i == BAND_CENTERS.lastIndex) 20000f else kotlin.math.sqrt(BAND_CENTERS[i] * BAND_CENTERS[i + 1])
        }

        /**
         * Samples the same filter curves the in-app chain uses at each band centre, then applies the output's boost
         * budget. Without a limiter (Equalizer fallback) net boost is held at 0 dB.
         */
        fun computeTargets(
            s: SoundSettings, safety: OutputSafety, attenuationDb: Float, appBoostDb: Float, hasLimiter: Boolean,
        ): SessionTargets {
            val specs: List<FilterSpec> = buildList {
                addAll(ToneCurves.graphicEq(s.eqGainsDb)); addAll(ToneCurves.tone(s.bassDb, s.trebleDb))
                addAll(ToneCurves.voiceClarity(s.voiceClarity).filter { it.type != io.spatia.dsp.filters.FilterType.HIGH_PASS })
                if (s.loudnessCompensation) addAll(ToneCurves.loudnessCompensation(attenuationDb))
                if (s.hearingEnabled) addAll(ToneCurves.hearing(s.hearingCompensation))
                addAll(ToneCurves.correction(s).filter { it.type != io.spatia.dsp.filters.FilterType.HIGH_PASS && it.type != io.spatia.dsp.filters.FilterType.LOW_PASS })
            }
            val coeffs = specs.map { it.coeffs(48000) }
            val bands = FloatArray(BAND_CENTERS.size) { i ->
                coeffs.sumOf { it.magnitudeDb(BAND_CENTERS[i].toDouble(), 48000.0) }.toFloat().coerceIn(-24f, 24f)
            }
            val corrPre = if (s.correctionEnabled) s.correctionPreampDb else 0f
            val boost = SafetyPolicy.capAppBoostDb(appBoostDb, safety)
            val pre = s.preampDb + corrPre + boost
            val worst = (bands.maxOrNull() ?: 0f) + pre
            val budget = if (hasLimiter) safety else safety.copy(maxBoostDb = 0f)
            val headroom = SafetyPolicy.headroomGainDb(worst, budget)
            return SessionTargets(bands, pre + headroom, safety.limiterCeilingDb, s.nightCompression)
        }

        fun interpolate(a: SessionTargets, b: SessionTargets, t: Float) = SessionTargets(
            FloatArray(a.bandGainsDb.size) { a.bandGainsDb[it] + (b.bandGainsDb[it] - a.bandGainsDb[it]) * t },
            a.inputGainDb + (b.inputGainDb - a.inputGainDb) * t,
            b.limiterDb, b.night,
        )
    }
}
