package io.spatia.dsp.spatial

/** Mid/side width, mono fold-down and balance on an interleaved stereo buffer. */
object StereoTools {
    fun width(buf: FloatArray, frames: Int, width: Float, midGain: Float = 1f) {
        if (width == 1f && midGain == 1f) return
        for (f in 0 until frames) {
            val l = buf[2 * f]; val r = buf[2 * f + 1]
            val m = (l + r) * 0.5f * midGain
            val s = (l - r) * 0.5f * width
            buf[2 * f] = m + s
            buf[2 * f + 1] = m - s
        }
    }

    fun mono(buf: FloatArray, frames: Int) {
        for (f in 0 until frames) {
            val m = (buf[2 * f] + buf[2 * f + 1]) * 0.5f
            buf[2 * f] = m; buf[2 * f + 1] = m
        }
    }

    /** Constant-loudness-at-centre balance: the far side is attenuated, the near side untouched. */
    fun balance(buf: FloatArray, frames: Int, balance: Float) {
        if (balance == 0f) return
        val gl = if (balance > 0) 1f - balance else 1f
        val gr = if (balance < 0) 1f + balance else 1f
        for (f in 0 until frames) { buf[2 * f] *= gl; buf[2 * f + 1] *= gr }
    }
}
