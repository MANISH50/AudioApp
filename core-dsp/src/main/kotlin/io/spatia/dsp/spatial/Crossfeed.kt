package io.spatia.dsp.spatial

import io.spatia.dsp.Db
import kotlin.math.PI
import kotlin.math.exp

/**
 * Headphone crossfeed in the style of Bauer stereophonic-to-binaural: each ear receives a low-passed, slightly
 * delayed, attenuated copy of the opposite channel, then the sum is normalised so mono content keeps its level.
 */
class Crossfeed(private val sampleRate: Int) {
    private val lpCoef = exp(-2 * PI * 700.0 / sampleRate).toFloat()
    private val delayLen = maxOf(1, (sampleRate * 0.0003).toInt())
    private val dL = FloatArray(delayLen); private val dR = FloatArray(delayLen)
    private var p = 0
    private var lpL = 0f; private var lpR = 0f

    /** 0 = off, 1 = strongest (feed at -4.5 dB). */
    var amount = 0f
        set(value) { field = value.coerceIn(0f, 1f); feed = if (field == 0f) 0f else Db.toLinear(-15f + 10.5f * field) }
    private var feed = 0f

    fun process(buf: FloatArray, frames: Int) {
        if (feed == 0f) return
        val norm = 1f / (1f + feed)
        for (f in 0 until frames) {
            val l = buf[2 * f]; val r = buf[2 * f + 1]
            lpL = l + (lpL - l) * lpCoef
            lpR = r + (lpR - r) * lpCoef
            val crossToR = dL[p]; val crossToL = dR[p]
            dL[p] = lpL; dR[p] = lpR
            p = (p + 1) % delayLen
            buf[2 * f] = (l + feed * crossToL) * norm
            buf[2 * f + 1] = (r + feed * crossToR) * norm
        }
    }

    fun reset() { dL.fill(0f); dR.fill(0f); lpL = 0f; lpR = 0f }
}
