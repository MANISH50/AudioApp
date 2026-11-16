package io.spatia.dsp

import io.spatia.dsp.dynamics.Compressor
import io.spatia.dsp.dynamics.Limiter
import io.spatia.dsp.dynamics.LoudnessNormalizer
import io.spatia.dsp.filters.BassEnhancer
import io.spatia.dsp.filters.Convolver
import io.spatia.dsp.filters.FilterBank
import io.spatia.dsp.filters.FilterSpec
import io.spatia.dsp.filters.ToneCurves
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import io.spatia.dsp.spatial.BinauralRenderer
import io.spatia.dsp.spatial.Crossfeed
import io.spatia.dsp.spatial.Reverb
import io.spatia.dsp.spatial.StereoTools
import io.spatia.dsp.spatial.Upmixer
import kotlin.math.sqrt

/**
 * The complete in-app processing chain. Input: interleaved float PCM with [inChannels] (1, 2, 6 or 8). Output: always
 * interleaved stereo float.
 *
 * Threading: the setters may be called from any thread; they publish immutable snapshots that the audio thread picks
 * up at the next 128-frame sub-block. [process] must only be called from one (the audio) thread.
 */
class DspChain(val sampleRate: Int, val inChannels: Int) {
    init {
        require(sampleRate in 8000..384000) { "Unsupported sample rate" }
        require(inChannels in SUPPORTED_CHANNELS) { "Unsupported channel count $inChannels" }
    }

    private class Params(
        val settings: SoundSettings,
        val output: OutputType,
        val volumeAttenuationDb: Float,
    )

    @Volatile private var pending = Params(SoundSettings(), OutputType.SPEAKER, 0f)
    @Volatile private var headYawRad = 0.0
    @Volatile private var pendingConvolver: Convolver? = null
    @Volatile private var convolverChanged = false

    // ---- audio-thread state ----
    private var cur = SoundSettings()
    private var target = SoundSettings()
    private var curOutput = OutputType.SPEAKER
    private var safety = OutputSafety.forType(curOutput)
    private var attenuationDb = 0f
    private var activeKey: List<Any> = SettingsSmoother.structureKey(cur)
    private var smoothedYaw = 0.0

    private val filters = FilterBank(2)
    private var filterSpecs: List<FilterSpec> = emptyList()
    private val preGain = ParamRamp(1f, sampleRate / 50)
    private val duck = ParamRamp(1f, sampleRate / 200) // 5 ms fade for graph changes
    private val bypassMix = ParamRamp(0f, sampleRate / 20)
    private var awaitingSwap = false

    private val limiter = Limiter(2, sampleRate)
    private val compressor = Compressor(2, sampleRate)
    private val normalizer = LoudnessNormalizer(2, sampleRate)
    private val crossfeed = Crossfeed(sampleRate)
    private val bass = BassEnhancer(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val upmixer = Upmixer(sampleRate)
    private val speakerCount = if (inChannels > 2) inChannels else 5
    private val renderer = BinauralRenderer(speakerCount, sampleRate)
    private var convolver: Convolver? = null

    private val work = FloatArray(BLOCK * 2)
    private val dry = FloatArray(BLOCK * 2)
    private val wet = FloatArray(BLOCK * 2)
    private val monoBuf = FloatArray(BLOCK)
    private val revL = FloatArray(BLOCK)
    private val revR = FloatArray(BLOCK)
    private val speakers = Array(speakerCount) { FloatArray(BLOCK) }
    private var dryPower = 1e-9
    private var wetPower = 1e-9
    private val azimuths = DoubleArray(speakerCount)

    val latencyFrames: Int get() = limiter.latencyFrames
    val limiterReductionDb: Float get() = limiter.gainReductionDb
    val normalizerGainDb: Float get() = normalizer.currentGainDb

    init { compressor.thresholdDb = -32f; compressor.ratio = 4f; compressor.makeupDb = 8f }

    fun setSettings(settings: SoundSettings, output: OutputType, volumeAttenuationDb: Float = 0f) {
        pending = Params(settings.validated(), output, volumeAttenuationDb.safeClamp(0f, 90f, 0f))
    }

    /** Head yaw in radians (+ = turned right). Only applied when head tracking is enabled. */
    fun setHeadYaw(yawRad: Double) { if (yawRad.isFinite()) headYawRad = yawRad }

    fun setConvolver(c: Convolver?) { pendingConvolver = c; convolverChanged = true }

    /** Process [frames] frames from [input] (interleaved, [inChannels]) into [output] (interleaved stereo). */
    fun process(input: FloatArray, output: FloatArray, frames: Int) {
        require(input.size >= frames * inChannels && output.size >= frames * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            processBlock(input, done * inChannels, output, done * 2, n)
            done += n
        }
    }

    private fun pickUpParams() {
        val p = pending
        target = p.settings
        if (p.output != curOutput) { curOutput = p.output; safety = OutputSafety.forType(curOutput) }
        attenuationDb = p.volumeAttenuationDb
        if (convolverChanged) { convolverChanged = false; convolver = pendingConvolver }
        limiter.ceilingDb = safety.limiterCeilingDb
        normalizer.maxGainDb = SafetyPolicy.maxNormalizerGainDb(safety)
    }

    private fun processBlock(input: FloatArray, inOff: Int, output: FloatArray, outOff: Int, n: Int) {
        pickUpParams()

        // Structural change: fade out, swap the graph, fade back in.
        val tKey = SettingsSmoother.structureKey(target)
        if (tKey != activeKey && !awaitingSwap) { awaitingSwap = true; duck.setTarget(0f) }
        if (awaitingSwap && duck.current == 0f) {
            cur = target; activeKey = tKey; awaitingSwap = false
            resetStateful(); rebuildFilters(); duck.setTarget(1f)
        } else if (!awaitingSwap) {
            val next = SettingsSmoother.step(cur, target, DB_STEP, UNIT_STEP)
            if (next != cur) { cur = next; rebuildFilters() }
        }
        val s = cur
        bypassMix.setTarget(if (target.bypass) 1f else 0f)

        // 1. Input -> stereo (dry reference is a plain downmix).
        toStereo(input, inOff, dry, n)
        if (s.spatialEnabled) renderSpatial(input, inOff, n, s) else dry.copyInto(work, 0, 0, n * 2)

        // 2. EQ family.
        filters.process(work, n)
        if (s.voiceClarity > 0f) StereoTools.width(work, n, 1f - 0.3f * s.voiceClarity, 1f + 0.3f * s.voiceClarity)
        if (!s.spatialEnabled) StereoTools.width(work, n, s.width)

        // 3. Space.
        if (s.spatialEnabled && s.roomSize > 0f) {
            for (f in 0 until n) { monoBuf[f] = (work[2 * f] + work[2 * f + 1]) * 0.5f; revL[f] = 0f; revR[f] = 0f }
            reverb.roomSize = s.roomSize
            reverb.process(monoBuf, n, revL, revR, 0.35f * s.roomSize)
            for (f in 0 until n) { work[2 * f] += revL[f]; work[2 * f + 1] += revR[f] }
        }
        val conv = convolver
        if (s.convolutionEnabled && conv != null) {
            conv.process(work, n, wet)
            val m = s.convolutionMix
            for (i in 0 until n * 2) work[i] = work[i] * (1 - m) + wet[i] * m
        }
        if (safety.isHeadphones && !s.spatialEnabled) { crossfeed.amount = s.crossfeed; crossfeed.process(work, n) }
        if (curOutput == OutputType.SPEAKER) { bass.amount = s.psychoBass; bass.process(work, n) }
        if (s.mono) StereoTools.mono(work, n)
        StereoTools.balance(work, n, s.balance)

        // 4. Gain staging and dynamics.
        for (f in 0 until n) {
            val g = preGain.next() * duck.next()
            work[2 * f] *= g; work[2 * f + 1] *= g
        }
        if (s.loudnessNormalization) normalizer.process(work, n)
        if (s.nightCompression) compressor.process(work, n)

        // 5. Loudness-matched A/B bypass.
        var dp = 0.0; var wp = 0.0
        for (i in 0 until n * 2) { dp += dry[i] * dry[i]; wp += work[i] * work[i] }
        dryPower = dryPower * 0.98 + dp * 0.02; wetPower = wetPower * 0.98 + wp * 0.02
        val match = sqrt(wetPower / dryPower.coerceAtLeast(1e-12)).toFloat().coerceIn(0.1f, Db.toLinear(safety.maxBoostDb))
        for (f in 0 until n) {
            val b = bypassMix.next()
            if (b > 0f) {
                val i = 2 * f
                work[i] = work[i] * (1 - b) + dry[i] * match * b
                work[i + 1] = work[i + 1] * (1 - b) + dry[i + 1] * match * b
            }
        }

        // 6. Safety limiter: always on, cannot be disabled.
        limiter.process(work, n)
        work.copyInto(output, outOff, 0, n * 2)
    }

    private fun rebuildFilters() {
        val s = cur
        val specs = buildList {
            addAll(ToneCurves.graphicEq(s.eqGainsDb))
            addAll(ToneCurves.tone(s.bassDb, s.trebleDb))
            addAll(ToneCurves.voiceClarity(s.voiceClarity))
            if (s.loudnessCompensation) addAll(ToneCurves.loudnessCompensation(attenuationDb))
            if (s.hearingEnabled) addAll(ToneCurves.hearing(s.hearingCompensation))
            addAll(ToneCurves.correction(s))
        }
        filterSpecs = specs
        filters.setFilters(specs, sampleRate)
        val corrPre = if (s.correctionEnabled) s.correctionPreampDb else 0f
        val worst = ToneCurves.maxBoostDb(specs, sampleRate, s.preampDb + corrPre)
        val headroom = SafetyPolicy.headroomGainDb(worst, safety)
        preGain.setTarget(Db.toLinear(s.preampDb + corrPre + headroom))
    }

    /** Current filter curve for UI plotting. */
    fun currentFilterSpecs(): List<FilterSpec> = filterSpecs

    private fun resetStateful() {
        filters.reset(); renderer.reset(); reverb.reset(); upmixer.reset(); crossfeed.reset(); bass.reset()
        compressor.reset()
    }

    private fun toStereo(input: FloatArray, off: Int, out: FloatArray, n: Int) {
        when (inChannels) {
            1 -> for (f in 0 until n) { val v = input[off + f]; out[2 * f] = v; out[2 * f + 1] = v }
            2 -> input.copyInto(out, 0, off, off + n * 2)
            else -> for (f in 0 until n) {
                val b = off + f * inChannels
                // ITU-R BS.775 style downmix with -6 dB overall headroom; LFE omitted.
                var l = input[b] + 0.707f * input[b + 2] + 0.707f * input[b + 4]
                var r = input[b + 1] + 0.707f * input[b + 2] + 0.707f * input[b + 5]
                if (inChannels == 8) { l += 0.707f * input[b + 6]; r += 0.707f * input[b + 7] }
                out[2 * f] = l * 0.5f; out[2 * f + 1] = r * 0.5f
            }
        }
    }

    private fun renderSpatial(input: FloatArray, off: Int, n: Int, s: SoundSettings) {
        val yawTarget = if (s.headTracking) headYawRad else 0.0
        smoothedYaw += (yawTarget - smoothedYaw) * 0.3
        val base: DoubleArray
        if (inChannels > 2) {
            base = if (inChannels == 6) SURROUND_51 else SURROUND_71
            for (f in 0 until n) {
                val b = off + f * inChannels
                for (c in 0 until inChannels) speakers[c][f] = if (c == 3) input[b + c] * 0.5f else input[b + c]
            }
        } else {
            val spread = Math.toRadians(10.0 + 35.0 * s.width)
            if (s.surroundUpmix) {
                upmixer.process(dry, n, speakers)
                base = doubleArrayOf(-spread, spread, 0.0, Math.toRadians(-110.0), Math.toRadians(110.0))
            } else {
                for (f in 0 until n) {
                    speakers[0][f] = dry[2 * f]; speakers[1][f] = dry[2 * f + 1]
                    speakers[2][f] = 0f; speakers[3][f] = 0f; speakers[4][f] = 0f
                }
                base = doubleArrayOf(-spread, spread, 0.0, Math.toRadians(-110.0), Math.toRadians(110.0))
            }
        }
        // Turning the head right moves the virtual sources left relative to the head.
        for (i in 0 until speakerCount) azimuths[i] = base[i] - smoothedYaw
        renderer.setAzimuths(azimuths)
        renderer.process(speakers, n, work)
    }

    companion object {
        const val BLOCK = 128
        private const val DB_STEP = 0.2f
        private const val UNIT_STEP = 0.02f
        val SUPPORTED_CHANNELS = setOf(1, 2, 6, 8)
        private val SURROUND_51 = doubleArrayOf(-30.0, 30.0, 0.0, 0.0, -110.0, 110.0).map { Math.toRadians(it) }.toDoubleArray()
        private val SURROUND_71 = doubleArrayOf(-30.0, 30.0, 0.0, 0.0, -150.0, 150.0, -90.0, 90.0).map { Math.toRadians(it) }.toDoubleArray()
    }
}
