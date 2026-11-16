package io.spatia.dsp.spatial

import kotlin.math.PI
import kotlin.math.floor

/**
 * Renders N virtual loudspeakers to two ears using [HeadModel]. Each speaker gets a per-ear fractional delay and
 * head-shadow filter. Azimuths are updated at block rate (e.g. from a head tracker); filter coefficients are
 * interpolated across the block so moving sources don't click.
 */
class BinauralRenderer(private val speakerCount: Int, private val sampleRate: Int) {
    private val maxDelay = (sampleRate * 0.0015).toInt() + 4
    private val lines = Array(speakerCount) { FloatArray(maxDelay + 1) }
    private var pos = 0

    private class Ear {
        var b0 = 1.0; var b1 = 0.0; var a1 = 0.0
        var tb0 = 1.0; var tb1 = 0.0; var ta1 = 0.0
        var delay = 0.0; var tDelay = 0.0
        var x1 = 0.0; var y1 = 0.0
    }
    private val left = Array(speakerCount) { Ear() }
    private val right = Array(speakerCount) { Ear() }
    private val allEars = left + right
    private var initialised = false

    /** Set target azimuths (radians, 0 = front, +right), already including head-tracking yaw compensation. */
    fun setAzimuths(azimuths: DoubleArray) {
        require(azimuths.size == speakerCount)
        val baseItd = HeadModel.HEAD_RADIUS_M / HeadModel.SPEED_OF_SOUND // keep both delays positive
        for (i in 0 until speakerCount) {
            val az = HeadModel.wrap(azimuths[i])
            val itd = HeadModel.itdSeconds(az) // >0: source on the right, left ear is later
            setEar(left[i], az + PI / 2, (baseItd + itd / 2) * sampleRate)
            setEar(right[i], PI / 2 - az, (baseItd - itd / 2) * sampleRate)
        }
        if (!initialised) {
            allEars.forEach { it.b0 = it.tb0; it.b1 = it.tb1; it.a1 = it.ta1; it.delay = it.tDelay }
            initialised = true
        }
    }

    private fun setEar(e: Ear, thetaFromEar: Double, delaySamples: Double) {
        val c = HeadModel.shadowCoeffs(HeadModel.wrap(thetaFromEar), sampleRate)
        e.tb0 = c[0]; e.tb1 = c[1]; e.ta1 = c[2]
        e.tDelay = delaySamples.coerceIn(0.0, (maxDelay - 2).toDouble())
    }

    /**
     * [inputs] holds one de-interleaved buffer per speaker; output is written interleaved stereo into [out]
     * (overwriting). Gain is normalised by speaker count's power sum.
     */
    fun process(inputs: Array<FloatArray>, frames: Int, out: FloatArray) {
        val norm = (1.0 / kotlin.math.sqrt(speakerCount.toDouble() / 2.0)).coerceAtMost(1.0)
        val inv = 1.0 / frames.coerceAtLeast(1)
        for (f in 0 until frames) {
            val t = (f + 1) * inv
            var l = 0.0; var r = 0.0
            for (s in 0 until speakerCount) {
                lines[s][pos] = inputs[s][f]
                l += tap(s, left[s], t)
                r += tap(s, right[s], t)
            }
            out[2 * f] = (l * norm).toFloat()
            out[2 * f + 1] = (r * norm).toFloat()
            pos = (pos + 1) % lines[0].size
        }
        for (e in allEars) { e.b0 = e.tb0; e.b1 = e.tb1; e.a1 = e.ta1; e.delay = e.tDelay }
    }

    private fun tap(s: Int, e: Ear, t: Double): Double {
        val line = lines[s]
        val n = line.size
        val d = e.delay + (e.tDelay - e.delay) * t
        val di = floor(d).toInt()
        val frac = d - di
        val i0 = (pos - di + n) % n
        val i1 = (i0 - 1 + n) % n
        val x = line[i0] * (1 - frac) + line[i1] * frac
        val b0 = e.b0 + (e.tb0 - e.b0) * t
        val b1 = e.b1 + (e.tb1 - e.b1) * t
        val a1 = e.a1 + (e.ta1 - e.a1) * t
        val y = b0 * x + b1 * e.x1 - a1 * e.y1
        e.x1 = x; e.y1 = if (kotlin.math.abs(y) < 1e-25) 0.0 else y
        return y
    }

    fun reset() {
        lines.forEach { it.fill(0f) }
        allEars.forEach { it.x1 = 0.0; it.y1 = 0.0 }
    }
}
