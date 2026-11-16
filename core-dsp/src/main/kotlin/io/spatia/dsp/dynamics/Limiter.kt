package io.spatia.dsp.dynamics

import io.spatia.dsp.Db
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Look-ahead brick-wall peak limiter, linked across channels.
 *
 * Gain path: required gain per frame -> sliding minimum over the look-ahead window -> box average of the same
 * length. Because every value inside the average window already includes the current frame's requirement, the
 * smoothed gain is <= the required gain when that frame leaves the delay line, so the ceiling is never exceeded
 * (a final clamp guards against float rounding). Release is a one-pole that can only lower the gain further.
 */
class Limiter(
    private val channels: Int,
    sampleRate: Int,
    lookAheadMs: Float = 2f,
    releaseMs: Float = 80f,
) {
    private val window = maxOf(1, (sampleRate * lookAheadMs / 1000f).toInt())
    private val delay = Array(channels) { FloatArray(window) }
    private var writePos = 0

    // Sliding-minimum monotonic deque over the last `window + 1` required gains.
    private val dqVal = FloatArray(window + 2)
    private val dqIdx = LongArray(window + 2)
    private var dqHead = 0
    private var dqSize = 0
    private var frameCounter = 0L

    // Box average of the sliding minimum.
    private val boxBuf = FloatArray(window) { 1f }
    private var boxSum = window.toDouble()
    private var boxPos = 0

    private val releaseCoef = exp(-1.0 / (sampleRate * releaseMs / 1000.0)).toFloat()
    private var gain = 1f

    var ceilingDb: Float = -1f
        set(value) { field = value.coerceIn(-24f, 0f); ceiling = Db.toLinear(field) }
    private var ceiling = Db.toLinear(ceilingDb)

    /** Most recent gain reduction in dB (<= 0), for metering. */
    var gainReductionDb: Float = 0f
        private set

    val latencyFrames: Int get() = window

    fun process(buf: FloatArray, frames: Int) {
        var idx = 0
        for (f in 0 until frames) {
            var peak = 0f
            for (ch in 0 until channels) peak = maxOf(peak, abs(buf[idx + ch]))
            val required = if (peak > ceiling) ceiling / peak else 1f

            pushMin(required)
            val minG = dqVal[dqHead]

            boxSum += minG - boxBuf[boxPos]
            boxBuf[boxPos] = minG
            boxPos = (boxPos + 1) % window
            if (boxPos == 0) boxSum = boxBuf.sumOf { it.toDouble() } // cancel accumulated rounding drift
            val target = (boxSum / window).toFloat()

            gain = if (target < gain) target else min(target, target + (gain - target) * releaseCoef)
            gainReductionDb = Db.fromLinear(gain)

            for (ch in 0 until channels) {
                val line = delay[ch]
                val out = line[writePos] * gain
                line[writePos] = buf[idx + ch]
                buf[idx + ch] = out.coerceIn(-ceiling, ceiling)
            }
            writePos = (writePos + 1) % window
            idx += channels
        }
    }

    private fun pushMin(v: Float) {
        val cap = dqVal.size
        while (dqSize > 0) {
            val tail = (dqHead + dqSize - 1) % cap
            if (dqVal[tail] >= v) dqSize-- else break
        }
        val pos = (dqHead + dqSize) % cap
        dqVal[pos] = v; dqIdx[pos] = frameCounter; dqSize++
        // Window spans window+1 frames so that, after the window-frame delay, every averaged minimum includes the
        // frame being output.
        while (dqIdx[dqHead] <= frameCounter - window - 1) { dqHead = (dqHead + 1) % cap; dqSize-- }
        frameCounter++
    }

    fun reset() {
        delay.forEach { it.fill(0f) }
        boxBuf.fill(1f); boxSum = window.toDouble(); boxPos = 0
        dqHead = 0; dqSize = 0; gain = 1f
    }
}
