package io.spatia.dsp.apo

import io.spatia.dsp.Db
import io.spatia.dsp.SafetyPolicy
import io.spatia.dsp.filters.FilterSpec
import io.spatia.dsp.filters.ToneCurves
import io.spatia.dsp.model.EqFilter
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import kotlin.math.abs
import kotlin.math.max
import kotlinx.serialization.Serializable

/**
 * What a system-wide equaliser host (Equalizer APO on Windows) has to do to approximate Spatia's chain for one set of
 * settings. Equalizer APO offers biquads, preamp, channel matrices (`Copy`), delay and per-channel convolution, but no
 * dynamics, so the plan is built for a chain *without* a limiter: the net boost never exceeds 0 dB.
 *
 * Matrices are row-major 2x2: `L' = m[0]*L + m[1]*R`, `R' = m[2]*L + m[3]*R`. [preMatrix] runs after the filters
 * (voice focus, width), [postMatrix] after the room (crossfeed, mono, balance).
 */
@Serializable
data class ApoPlan(
    val bypass: Boolean,
    val preampDb: Float,
    val filters: List<EqFilter>,
    val preMatrix: List<Float>,
    val postMatrix: List<Float>,
    /** True when the room (environment or spatial room) needs an impulse response; a user IR adds to this. */
    val needsImpulse: Boolean,
    /** Features that Equalizer APO can only approximate or cannot do, as stable ids (UI shows a note per id). */
    val approximations: List<String>,
)

object ApoPlanner {
    const val SAMPLE_RATE = 48000
    val IDENTITY: List<Float> = listOf(1f, 0f, 0f, 1f)

    /** Night mode has no compressor in Equalizer APO: a quieter preamp and a gentle loudness contour stand in. */
    const val NIGHT_PREAMP_DB = -3f
    const val NIGHT_CONTOUR_ATTENUATION_DB = 12f

    const val APPROX_NIGHT = "night"
    const val APPROX_NORMALIZATION = "normalization"
    const val APPROX_PSYCHO_BASS = "psychoBass"
    const val APPROX_SPATIAL = "spatial"
    const val APPROX_CROSSFEED = "crossfeed"

    fun plan(input: SoundSettings, output: OutputType, attenuationDb: Float): ApoPlan {
        val s = input.validated()
        if (s.bypass) return ApoPlan(true, 0f, emptyList(), IDENTITY, IDENTITY, false, emptyList())
        val safety = OutputSafety.forType(output)
        val att = if (attenuationDb.isFinite()) attenuationDb.coerceIn(0f, 90f) else 0f

        val specs = filterSpecs(s, att)
        val corrPre = if (s.correctionEnabled) s.correctionPreampDb else 0f
        val pre = s.preampDb + corrPre
        val worst = ToneCurves.maxBoostDb(specs, SAMPLE_RATE, pre)
        // No limiter after Equalizer APO, so the boost budget is 0 dB: boosts can never clip.
        var preamp = pre + SafetyPolicy.headroomGainDb(worst, safety.copy(maxBoostDb = 0f))
        if (s.nightCompression) preamp += NIGHT_PREAMP_DB

        val preM = preMatrix(s)
        val postM = postMatrix(s, safety)
        preamp += matrixHeadroomDb(preM) + matrixHeadroomDb(postM)

        val approx = buildList {
            if (s.nightCompression) add(APPROX_NIGHT)
            if (s.loudnessNormalization) add(APPROX_NORMALIZATION)
            if (output == OutputType.SPEAKER && s.psychoBass > 0f) add(APPROX_PSYCHO_BASS)
            if (s.spatialEnabled) add(APPROX_SPATIAL)
            if (safety.isHeadphones && !s.spatialEnabled && s.crossfeed > 0f) add(APPROX_CROSSFEED)
        }
        return ApoPlan(
            bypass = false,
            preampDb = preamp.coerceIn(-60f, 0f),
            filters = specs.map { EqFilter(it.type, it.freqHz, it.gainDb, it.q) },
            preMatrix = preM,
            postMatrix = postM,
            needsImpulse = ApoImpulse.needsImpulse(s, hasUserImpulse = false),
            approximations = approx,
        )
    }

    /** The same biquad list as the in-app chain (DspChain.rebuildFilters), plus the night-mode contour. */
    fun filterSpecs(s: SoundSettings, attenuationDb: Float): List<FilterSpec> = buildList {
        addAll(ToneCurves.graphicEq(s.eqGainsDb))
        addAll(ToneCurves.tone(s.bassDb, s.trebleDb))
        addAll(ToneCurves.voiceClarity(s.voiceClarity))
        when {
            s.loudnessCompensation -> addAll(ToneCurves.loudnessCompensation(attenuationDb))
            s.nightCompression -> addAll(ToneCurves.loudnessCompensation(NIGHT_CONTOUR_ATTENUATION_DB))
        }
        if (s.hearingEnabled) addAll(ToneCurves.hearing(s.hearingCompensation))
        addAll(ToneCurves.correction(s))
    }

    /** Voice focus (mid lift, side cut) and stereo width, as in DspChain (the spatial renderer's spread is approximated by width). */
    fun preMatrix(s: SoundSettings): List<Float> {
        var m = IDENTITY
        if (s.voiceClarity > 0f) m = multiply(widthMatrix(1f - 0.3f * s.voiceClarity, 1f + 0.3f * s.voiceClarity), m)
        if (s.width != 1f) m = multiply(widthMatrix(s.width, 1f), m)
        return m
    }

    /** Crossfeed (flat approximation of the low-passed Bauer feed), mono and balance. */
    fun postMatrix(s: SoundSettings, safety: OutputSafety): List<Float> {
        var m = IDENTITY
        if (safety.isHeadphones && !s.spatialEnabled && s.crossfeed > 0f) {
            // Only frequencies below ~700 Hz are fed across in the real crossfeed; a flat feed at half the level is
            // a reasonable broadband stand-in.
            val feed = Db.toLinear(-15f + 10.5f * s.crossfeed) * 0.5f
            val n = 1f / (1f + feed)
            m = multiply(listOf(n, feed * n, feed * n, n), m)
        }
        if (s.mono) m = multiply(listOf(0.5f, 0.5f, 0.5f, 0.5f), m)
        if (s.balance != 0f) {
            val gl = if (s.balance > 0) 1f - s.balance else 1f
            val gr = if (s.balance < 0) 1f + s.balance else 1f
            m = multiply(listOf(gl, 0f, 0f, gr), m)
        }
        return m
    }

    fun widthMatrix(width: Float, midGain: Float): List<Float> {
        val a = (midGain + width) * 0.5f
        val b = (midGain - width) * 0.5f
        return listOf(a, b, b, a)
    }

    /** [a] applied after [b]. */
    fun multiply(a: List<Float>, b: List<Float>): List<Float> = listOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
    )

    /** Pre-gain (<= 0 dB) so neither fully correlated (mid) nor anti-correlated (side) content gets louder. */
    fun matrixHeadroomDb(m: List<Float>): Float {
        val worst = max(
            max(abs(m[0] + m[1]), abs(m[0] - m[1])),
            max(abs(m[2] + m[3]), abs(m[2] - m[3])),
        )
        return if (worst <= 1f) 0f else -Db.fromLinear(worst)
    }

    fun isIdentity(m: List<Float>) = m.size == 4 && m.indices.all { abs(m[it] - IDENTITY[it]) < 1e-6f }
}
