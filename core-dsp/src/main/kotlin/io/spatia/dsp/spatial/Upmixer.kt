package io.spatia.dsp.spatial

import kotlin.math.PI
import kotlin.math.exp

/**
 * Passive stereo -> 5.0 upmix for virtualisation: centre from the mid signal, surrounds from a delayed,
 * low-passed side signal (decorrelated from the fronts so it is perceived as ambience behind the listener).
 */
class Upmixer(sampleRate: Int) {
    private val delayLen = maxOf(1, (sampleRate * 0.012).toInt())
    private val delay = FloatArray(delayLen)
    private var p = 0
    private val lpCoef = exp(-2 * PI * 7000.0 / sampleRate).toFloat()
    private var lp = 0f

    /**
     * Writes FL, FR, C, SL, SR into [out] (each sized >= frames) from interleaved stereo [buf].
     */
    fun process(buf: FloatArray, frames: Int, out: Array<FloatArray>, centre: Float = 0.5f, surround: Float = 0.7f) {
        for (f in 0 until frames) {
            val l = buf[2 * f]; val r = buf[2 * f + 1]
            val m = (l + r) * 0.5f
            val s = (l - r) * 0.5f
            val d = delay[p]; delay[p] = s; p = (p + 1) % delayLen
            lp = d + (lp - d) * lpCoef
            out[0][f] = l - m * centre
            out[1][f] = r - m * centre
            out[2][f] = m * centre * 1.414f
            out[3][f] = lp * surround
            out[4][f] = -lp * surround
        }
    }

    fun reset() { delay.fill(0f); lp = 0f }
}
