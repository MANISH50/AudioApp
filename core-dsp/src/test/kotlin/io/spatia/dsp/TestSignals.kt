package io.spatia.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object TestSignals {
    fun sineStereo(freq: Double, sr: Int, frames: Int, amp: Float = 0.5f): FloatArray =
        FloatArray(frames * 2) { i -> (amp * sin(2 * PI * freq * (i / 2) / sr)).toFloat() }

    fun noiseStereo(frames: Int, amp: Float, seed: Int = 1): FloatArray {
        val r = Random(seed)
        return FloatArray(frames * 2) { (r.nextFloat() * 2 - 1) * amp }
    }

    fun peak(buf: FloatArray, from: Int = 0, to: Int = buf.size): Float {
        var p = 0f
        for (i in from until to) p = maxOf(p, kotlin.math.abs(buf[i]))
        return p
    }

    fun rms(buf: FloatArray, from: Int = 0, to: Int = buf.size): Double {
        var s = 0.0
        for (i in from until to) s += buf[i] * buf[i]
        return kotlin.math.sqrt(s / (to - from))
    }
}
