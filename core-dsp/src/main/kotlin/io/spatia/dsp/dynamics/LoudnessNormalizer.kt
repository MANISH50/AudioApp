package io.spatia.dsp.dynamics

import io.spatia.dsp.Db
import io.spatia.dsp.filters.Biquad
import io.spatia.dsp.filters.BiquadCoeffs
import io.spatia.dsp.filters.FilterType
import kotlin.math.exp
import kotlin.math.log10

/**
 * Slow automatic gain toward a target loudness, measured with the ITU-R BS.1770 K-weighting pre-filter and a
 * ~3 s integrator (a short-term approximation, not a certified LUFS meter). Gain changes are limited to a slow
 * slew so it never "pumps", and quiet passages below the gate are not boosted.
 */
class LoudnessNormalizer(private val channels: Int, private val sampleRate: Int) {
    var targetLufs = -16f
    var maxGainDb = 6f
    var minGainDb = -12f
    private val gateLufs = -50f
    private val shelf = Array(channels) {
        Biquad(BiquadCoeffs.design(FilterType.HIGH_SHELF, 1681.97, 0.7071, 4.0, sampleRate.toDouble()))
    }
    private val hp = Array(channels) {
        Biquad(BiquadCoeffs.design(FilterType.HIGH_PASS, 38.13, 0.5, 0.0, sampleRate.toDouble()))
    }
    private val integCoef = exp(-1.0 / (sampleRate * 3.0))
    private var meanSquare = 0.0
    private var gainDb = 0f
    private val slewDbPerSample = 1f / sampleRate // 1 dB/s

    val measuredLufs: Float
        get() = if (meanSquare <= 1e-12) Db.SILENCE_DB else (-0.691 + 10 * log10(meanSquare)).toFloat()

    val currentGainDb: Float get() = gainDb

    fun process(buf: FloatArray, frames: Int) {
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0.0
            for (ch in 0 until channels) {
                val k = hp[ch].process(shelf[ch].process(buf[idx + ch])).toDouble()
                sum += k * k
            }
            meanSquare = sum + (meanSquare - sum) * integCoef
            if (f and 63 == 0) {
                val lufs = measuredLufs
                val desired = if (lufs < gateLufs) gainDb.coerceAtMost(0f)
                else (targetLufs - lufs).coerceIn(minGainDb, maxGainDb)
                val maxStep = slewDbPerSample * 64
                gainDb += (desired - gainDb).coerceIn(-maxStep, maxStep)
            }
            val g = Db.toLinear(gainDb)
            for (ch in 0 until channels) buf[idx + ch] *= g
            idx += channels
        }
    }

    fun reset() { meanSquare = 0.0; gainDb = 0f; shelf.forEach { it.reset() }; hp.forEach { it.reset() } }
}
