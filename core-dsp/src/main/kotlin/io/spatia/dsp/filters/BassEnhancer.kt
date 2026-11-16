package io.spatia.dsp.filters

import io.spatia.dsp.Db

/**
 * Psychoacoustic bass ("missing fundamental") for small speakers. Content below [cutoffHz] that the driver cannot
 * reproduce is full-wave rectified to create its harmonics, band-limited to the range the speaker *can* play, and
 * mixed back in. The original sub-bass is then high-passed away to protect the driver.
 * Output harmonics are bounded by the input level (|rectified| <= |x|).
 */
class BassEnhancer(private val sampleRate: Int, private val cutoffHz: Float = 150f) {
    private val extractLp = Biquad(BiquadCoeffs.design(FilterType.LOW_PASS, cutoffHz.toDouble(), 0.707, 0.0, sampleRate.toDouble()))
    private val extractHp = Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, 30.0, 0.707, 0.0, sampleRate.toDouble()))
    private val harmHp = Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, cutoffHz.toDouble(), 0.707, 0.0, sampleRate.toDouble()))
    private val harmLp = Biquad(BiquadCoeffs.design(FilterType.LOW_PASS, cutoffHz * 4.0, 0.707, 0.0, sampleRate.toDouble()))
    private val protectL = Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, cutoffHz * 0.5, 0.707, 0.0, sampleRate.toDouble()))
    private val protectR = Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, cutoffHz * 0.5, 0.707, 0.0, sampleRate.toDouble()))

    var amount = 0f
        set(value) { field = value.coerceIn(0f, 1f) }

    /** Interleaved stereo in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (amount == 0f) return
        val g = Db.toLinear(6f) * amount
        for (f in 0 until frames) {
            val l = buf[2 * f]; val r = buf[2 * f + 1]
            val sub = extractHp.process(extractLp.process((l + r) * 0.5f))
            val harm = harmLp.process(harmHp.process(kotlin.math.abs(sub))) * g
            buf[2 * f] = protectL.process(l) + harm
            buf[2 * f + 1] = protectR.process(r) + harm
        }
    }

    fun reset() = listOf(extractLp, extractHp, harmHp, harmLp, protectL, protectR).forEach { it.reset() }
}
