package io.spatia.dsp.filters

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType { PEAK, LOW_SHELF, HIGH_SHELF, LOW_PASS, HIGH_PASS, BAND_PASS }

/** Normalised biquad coefficients (a0 == 1). Robert Bristow-Johnson "Audio EQ Cookbook" formulas. */
data class BiquadCoeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    /** Magnitude response in dB at [freqHz]. Used by tests and the EQ curve UI. */
    fun magnitudeDb(freqHz: Double, sampleRate: Double): Double {
        val w = 2 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag.coerceAtLeast(1e-12))
    }

    companion object {
        val IDENTITY = BiquadCoeffs(1.0, 0.0, 0.0, 0.0, 0.0)

        fun design(type: FilterType, freqHz: Double, q: Double, gainDb: Double, sampleRate: Double): BiquadCoeffs {
            val f = freqHz.coerceIn(5.0, sampleRate * 0.49)
            val qq = q.coerceIn(0.05, 30.0)
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * f / sampleRate
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2 * qq)
            val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
            when (type) {
                FilterType.PEAK -> {
                    b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cw + s)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                    b2 = a * ((a + 1) - (a - 1) * cw - s)
                    a0 = (a + 1) + (a - 1) * cw + s
                    a1 = -2 * ((a - 1) + (a + 1) * cw)
                    a2 = (a + 1) + (a - 1) * cw - s
                }
                FilterType.HIGH_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cw + s)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                    b2 = a * ((a + 1) + (a - 1) * cw - s)
                    a0 = (a + 1) - (a - 1) * cw + s
                    a1 = 2 * ((a - 1) - (a + 1) * cw)
                    a2 = (a + 1) - (a - 1) * cw - s
                }
                FilterType.LOW_PASS -> {
                    b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
                FilterType.BAND_PASS -> {
                    b0 = alpha; b1 = 0.0; b2 = -alpha
                    a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
                }
            }
            return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/** Single-channel transposed direct form II biquad. Double state for low-frequency precision. */
class Biquad(var coeffs: BiquadCoeffs = BiquadCoeffs.IDENTITY) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val c = coeffs
        val xin = x.toDouble()
        val y = c.b0 * xin + z1
        z1 = c.b1 * xin - c.a1 * y + z2
        z2 = c.b2 * xin - c.a2 * y
        // Flush denormals / recover from any numeric blow-up.
        if (!y.isFinite()) { reset(); return 0f }
        if (kotlin.math.abs(z1) < 1e-25) z1 = 0.0
        if (kotlin.math.abs(z2) < 1e-25) z2 = 0.0
        return y.toFloat()
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }
}
