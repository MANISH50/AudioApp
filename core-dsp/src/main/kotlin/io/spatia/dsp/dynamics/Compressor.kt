package io.spatia.dsp.dynamics

import io.spatia.dsp.Db
import kotlin.math.abs
import kotlin.math.exp

/** Feed-forward, stereo-linked, soft-knee compressor (used for Night mode). */
class Compressor(private val channels: Int, private val sampleRate: Int) {
    var thresholdDb = -30f
    var ratio = 4f
    var kneeDb = 6f
    var makeupDb = 6f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    private var envDb = Db.SILENCE_DB

    init { setTimes(5f, 200f) }

    fun setTimes(attackMs: Float, releaseMs: Float) {
        attackCoef = exp(-1.0 / (sampleRate * attackMs / 1000.0)).toFloat()
        releaseCoef = exp(-1.0 / (sampleRate * releaseMs / 1000.0)).toFloat()
    }

    /** Static curve: output level for an input level (dB). Exposed for tests. */
    fun staticCurveDb(inDb: Float): Float {
        val over = inDb - thresholdDb
        return when {
            2 * over < -kneeDb -> inDb
            2 * abs(over) <= kneeDb -> inDb + (1f / ratio - 1f) * (over + kneeDb / 2f).let { it * it } / (2f * kneeDb)
            else -> thresholdDb + over / ratio
        }
    }

    fun process(buf: FloatArray, frames: Int) {
        var idx = 0
        val makeup = makeupDb
        for (f in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = maxOf(peak, abs(buf[idx + ch]))
            val inDb = Db.fromLinear(peak)
            val coef = if (inDb > envDb) attackCoef else releaseCoef
            envDb = inDb + (envDb - inDb) * coef
            val g = Db.toLinear(staticCurveDb(envDb) - envDb + makeup)
            for (ch in 0 until channels) buf[idx + ch] *= g
            idx += channels
        }
    }

    fun reset() { envDb = Db.SILENCE_DB }
}
