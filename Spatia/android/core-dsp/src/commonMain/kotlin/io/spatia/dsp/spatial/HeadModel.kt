package io.spatia.dsp.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Parametric spherical-head HRTF (Brown & Duda, 1998): Woodworth ITD plus a first-order head-shadow filter.
 * Chosen over measured SOFA sets because it carries no dataset licence and costs a few multiplies per sample.
 */
object HeadModel {
    const val HEAD_RADIUS_M = 0.0875
    const val SPEED_OF_SOUND = 343.0
    private const val ALPHA_MIN = 0.1
    private const val THETA_MIN_DEG = 150.0

    /** Interaural time difference (s) for a source at [azimuthRad] (0 = front, +right), Woodworth formula. */
    fun itdSeconds(azimuthRad: Double): Double {
        var a = wrap(azimuthRad)
        // Rear sources mirror front ones for ITD purposes.
        if (a > PI / 2) a = PI - a
        if (a < -PI / 2) a = -PI - a
        return HEAD_RADIUS_M / SPEED_OF_SOUND * (a + sin(a))
    }

    /** Shadow-filter alpha for angle [thetaRad] between source and the ear axis (0 = facing that ear). */
    fun alpha(thetaRad: Double): Double {
        val deg = io.spatia.dsp.toDegrees(abs(thetaRad)).coerceIn(0.0, 180.0)
        return (1 + ALPHA_MIN / 2) + (1 - ALPHA_MIN / 2) * cos(deg / THETA_MIN_DEG * PI)
    }

    /** First-order shadow filter coefficients (b0, b1, a1) via bilinear transform. */
    fun shadowCoeffs(thetaRad: Double, sampleRate: Int): DoubleArray {
        val w0 = SPEED_OF_SOUND / HEAD_RADIUS_M
        val t = 1.0 / (2 * w0)
        val k = 2.0 * sampleRate
        val al = alpha(thetaRad)
        val a0 = 1 + t * k
        return doubleArrayOf((1 + al * t * k) / a0, (1 - al * t * k) / a0, (1 - t * k) / a0)
    }

    fun wrap(rad: Double): Double {
        var r = rad % (2 * PI)
        if (r > PI) r -= 2 * PI
        if (r < -PI) r += 2 * PI
        return r
    }
}
