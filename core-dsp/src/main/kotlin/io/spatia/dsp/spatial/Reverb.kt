package io.spatia.dsp.spatial

import kotlin.math.pow

/**
 * 4-line feedback delay network with a Hadamard mixing matrix and in-loop damping. [roomSize] 0..1 scales both
 * delay lengths and decay time (RT60 0.2..1.8 s).
 */
class Reverb(private val sampleRate: Int) {
    private val baseDelays = intArrayOf(1031, 1327, 1523, 1871) // at 48 kHz, mutually prime
    private val lines = Array(4) { FloatArray((baseDelays[it] * sampleRate / 48000.0 * 1.05).toInt() + 2) }
    private val lengths = IntArray(4)
    private val pos = IntArray(4)
    private val fb = FloatArray(4)
    private val lp = FloatArray(4)
    private var damp = 0.3f

    var roomSize = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            val scale = 0.35 + 0.65 * field
            val rt60 = 0.2 + 1.6 * field
            for (i in 0 until 4) {
                lengths[i] = (baseDelays[i] * sampleRate / 48000.0 * scale).toInt().coerceIn(1, lines[i].size - 1)
                fb[i] = 10.0.pow(-3.0 * lengths[i] / (rt60 * sampleRate)).toFloat()
            }
            damp = 0.2f + 0.4f * (1 - field)
        }

    init { roomSize = 0f }

    /** Mono in, writes stereo wet signal (adds into [outL]/[outR] scaled by [wet]). */
    fun process(input: FloatArray, frames: Int, outL: FloatArray, outR: FloatArray, wet: Float) {
        val y = FloatArray(4)
        for (f in 0 until frames) {
            for (i in 0 until 4) {
                val line = lines[i]
                val rd = (pos[i] - lengths[i] + line.size) % line.size
                lp[i] += (line[rd] - lp[i]) * (1 - damp)
                y[i] = lp[i]
            }
            // Hadamard 4x4 / 2 (orthonormal)
            val h0 = (y[0] + y[1] + y[2] + y[3]) * 0.5f
            val h1 = (y[0] - y[1] + y[2] - y[3]) * 0.5f
            val h2 = (y[0] + y[1] - y[2] - y[3]) * 0.5f
            val h3 = (y[0] - y[1] - y[2] + y[3]) * 0.5f
            val x = input[f]
            write(0, x + h0 * fb[0]); write(1, x + h1 * fb[1]); write(2, x + h2 * fb[2]); write(3, x + h3 * fb[3])
            outL[f] += (y[0] + y[2]) * 0.5f * wet
            outR[f] += (y[1] + y[3]) * 0.5f * wet
        }
    }

    private fun write(i: Int, v: Float) {
        val line = lines[i]
        line[pos[i]] = if (v.isFinite()) v else 0f
        pos[i] = (pos[i] + 1) % line.size
    }

    fun reset() { lines.forEach { it.fill(0f) }; lp.fill(0f) }
}
