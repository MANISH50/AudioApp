package io.spatia.dsp.filters

import io.spatia.dsp.analysis.Fft

/**
 * Uniformly partitioned overlap-add FFT convolution for a stereo signal with a (mono or stereo) impulse response.
 * Latency is one block ([blockSize] frames), which is acceptable because the convolver only feeds the wet path.
 */
class Convolver(ir: Array<FloatArray>, private val blockSize: Int = 256) {
    private val n = blockSize * 2
    private val fft = Fft(n)
    private val irChannels = ir.size
    private val partitions: Int
    // [irChannel][partition] spectra
    private val irRe: Array<Array<DoubleArray>>
    private val irIm: Array<Array<DoubleArray>>
    // [inChannel][partition] input-spectrum delay line
    private val fdlRe = Array(2) { emptyArray<DoubleArray>() }
    private val fdlIm = Array(2) { emptyArray<DoubleArray>() }
    private var fdlPos = 0
    private val inBuf = Array(2) { FloatArray(blockSize) }
    private val outBuf = Array(2) { FloatArray(blockSize) }
    private val overlap = Array(2) { DoubleArray(blockSize) }
    private var fill = 0
    private val re = DoubleArray(n); private val im = DoubleArray(n)
    private val accRe = DoubleArray(n); private val accIm = DoubleArray(n)

    init {
        require(ir.isNotEmpty() && ir.size <= 2 && ir[0].isNotEmpty())
        val len = ir.maxOf { it.size }
        partitions = (len + blockSize - 1) / blockSize
        irRe = Array(irChannels) { Array(partitions) { DoubleArray(n) } }
        irIm = Array(irChannels) { Array(partitions) { DoubleArray(n) } }
        for (c in 0 until irChannels) for (p in 0 until partitions) {
            val r = irRe[c][p]; val i = irIm[c][p]
            for (k in 0 until blockSize) { val idx = p * blockSize + k; r[k] = if (idx < ir[c].size) ir[c][idx].toDouble() else 0.0 }
            fft.transform(r, i)
        }
        for (c in 0 until 2) {
            fdlRe[c] = Array(partitions) { DoubleArray(n) }
            fdlIm[c] = Array(partitions) { DoubleArray(n) }
        }
    }

    val latencyFrames: Int get() = blockSize

    /** Reads interleaved stereo [input], writes the wet signal interleaved into [wetOut]. */
    fun process(input: FloatArray, frames: Int, wetOut: FloatArray) {
        for (f in 0 until frames) {
            inBuf[0][fill] = input[2 * f]; inBuf[1][fill] = input[2 * f + 1]
            wetOut[2 * f] = outBuf[0][fill]; wetOut[2 * f + 1] = outBuf[1][fill]
            fill++
            if (fill == blockSize) { runBlock(); fill = 0 }
        }
    }

    private fun runBlock() {
        fdlPos = (fdlPos - 1 + partitions) % partitions
        for (c in 0 until 2) {
            re.fill(0.0); im.fill(0.0)
            for (k in 0 until blockSize) re[k] = inBuf[c][k].toDouble()
            fft.transform(re, im)
            re.copyInto(fdlRe[c][fdlPos]); im.copyInto(fdlIm[c][fdlPos])
        }
        for (c in 0 until 2) {
            val irc = if (irChannels == 2) c else 0
            accRe.fill(0.0); accIm.fill(0.0)
            for (p in 0 until partitions) {
                val slot = (fdlPos + p) % partitions
                val xr = fdlRe[c][slot]; val xi = fdlIm[c][slot]
                val hr = irRe[irc][p]; val hi = irIm[irc][p]
                for (k in 0 until n) {
                    accRe[k] += xr[k] * hr[k] - xi[k] * hi[k]
                    accIm[k] += xr[k] * hi[k] + xi[k] * hr[k]
                }
            }
            fft.transform(accRe, accIm, inverse = true)
            val ov = overlap[c]
            for (k in 0 until blockSize) {
                outBuf[c][k] = (accRe[k] + ov[k]).toFloat()
                ov[k] = accRe[k + blockSize]
            }
        }
    }
}
