package io.spatia.dsp.apo

import io.spatia.dsp.filters.Convolver
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.SoundSettings
import io.spatia.dsp.spatial.EnvironmentReverb
import io.spatia.dsp.spatial.Reverb
import kotlin.math.log10

/**
 * Renders the linear "space" part of Spatia's chain (spatial room, user impulse response mix, acoustic environment)
 * into a stereo impulse response for a convolution host such as Equalizer APO.
 *
 * The chain is fed a centred impulse (L = R = 1), so channel c of the result is what an ear hears for centred content.
 * A convolution host applies channel c of the IR to channel c of the audio, which is exact for centred content and
 * keeps panned content's room on its own side. The dry path is part of the IR (sample 0 is 1.0 when no user IR is
 * mixed in), so the original audio passes through and the room is added on top, exactly as in the app.
 */
object ApoImpulse {
    const val MIN_SECONDS = 2.0f
    const val MAX_SECONDS = 4.0f
    private const val FADE_SECONDS = 0.25f
    private const val BLOCK = 128

    fun needsImpulse(s: SoundSettings, hasUserImpulse: Boolean): Boolean =
        !s.bypass && (s.environment != Environment.OFF || (s.spatialEnabled && s.roomSize > 0f) ||
            (s.convolutionEnabled && hasUserImpulse))

    /** Length in seconds: long enough for the space's tail, within [MIN_SECONDS]..[MAX_SECONDS]. */
    fun seconds(s: SoundSettings): Float {
        val envTail = s.environment.preset?.scaled(s.environmentIntensity)?.rt60 ?: 0f
        val roomTail = if (s.spatialEnabled) 0.2f + 1.6f * s.roomSize else 0f
        return (maxOf(envTail, roomTail) + 0.5f).coerceIn(MIN_SECONDS, MAX_SECONDS)
    }

    /**
     * Stereo IR at [sampleRate]. [userImpulse] is an already prepared IR (see WavReader.prepareImpulse) or null.
     * Always finite; the last [FADE_SECONDS] fade out so a truncated tail ends smoothly.
     */
    fun render(input: SoundSettings, sampleRate: Int = ApoPlanner.SAMPLE_RATE, userImpulse: Array<FloatArray>? = null): Array<FloatArray> {
        require(sampleRate in 8000..192000)
        val s = input.validated()
        val frames = (seconds(s) * sampleRate).toInt()
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)

        val room = if (s.spatialEnabled && s.roomSize > 0f) Reverb(sampleRate).also { it.roomSize = s.roomSize } else null
        val conv = if (s.convolutionEnabled && userImpulse != null) Convolver(userImpulse) else null
        val env = if (s.environment != Environment.OFF) EnvironmentReverb(sampleRate).also {
            it.setPreset(s.environment.preset, s.environmentIntensity)
        } else null

        val work = FloatArray(BLOCK * 2)
        val wet = FloatArray(BLOCK * 2)
        val mono = FloatArray(BLOCK)
        val revL = FloatArray(BLOCK)
        val revR = FloatArray(BLOCK)
        var done = 0
        while (done < frames) {
            val n = minOf(BLOCK, frames - done)
            work.fill(0f)
            if (done == 0) { work[0] = 1f; work[1] = 1f }
            if (room != null) {
                for (f in 0 until n) { mono[f] = (work[2 * f] + work[2 * f + 1]) * 0.5f; revL[f] = 0f; revR[f] = 0f }
                room.process(mono, n, revL, revR, 0.35f * s.roomSize)
                for (f in 0 until n) { work[2 * f] += revL[f]; work[2 * f + 1] += revR[f] }
            }
            if (conv != null) {
                conv.process(work, n, wet)
                val m = s.convolutionMix
                for (i in 0 until n * 2) work[i] = work[i] * (1 - m) + wet[i] * m
            }
            env?.process(work, n)
            for (f in 0 until n) {
                outL[done + f] = work[2 * f].let { if (it.isFinite()) it else 0f }
                outR[done + f] = work[2 * f + 1].let { if (it.isFinite()) it else 0f }
            }
            done += n
        }
        val fade = minOf(frames - 1, (FADE_SECONDS * sampleRate).toInt())
        for (k in 0 until fade) {
            val g = k.toFloat() / fade
            val i = frames - 1 - k
            outL[i] *= g; outR[i] *= g
        }
        return arrayOf(outL, outR)
    }

    /**
     * Pre-gain (<= 0 dB) for the IR: its energy is the power gain for noise-like (music) signals, so attenuating by it
     * keeps the overall level where it was without the room.
     */
    fun headroomDb(ir: Array<FloatArray>): Float {
        val energy = ir.maxOf { c -> c.sumOf { (it * it).toDouble() } }
        return if (energy <= 1.0) 0f else (-10.0 * log10(energy)).toFloat()
    }
}
