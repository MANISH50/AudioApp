package io.spatia.dsp.io

import kotlin.math.roundToInt

/** Writes RIFF/WAVE files. Counterpart of [WavReader]; used for the sample track and rendered impulse responses. */
object WavWriter {
    fun write16(channels: Array<FloatArray>, sampleRate: Int): ByteArray {
        require(channels.isNotEmpty() && channels.all { it.size == channels[0].size })
        val ch = channels.size
        val frames = channels[0].size
        val data = frames * ch * 2
        val w = LittleEndian.Writer(44 + data)
        w.ascii("RIFF").i32(36 + data).ascii("WAVE")
        w.ascii("fmt ").i32(16).i16(1).i16(ch).i32(sampleRate).i32(sampleRate * ch * 2).i16(ch * 2).i16(16)
        w.ascii("data").i32(data)
        for (f in 0 until frames) for (c in 0 until ch) {
            w.i16((channels[c][f] * 32767f).roundToInt().coerceIn(-32768, 32767))
        }
        return w.bytes
    }

    /** 32-bit IEEE float (WAVE_FORMAT_IEEE_FLOAT); non-finite samples are written as 0. */
    fun writeFloat32(channels: Array<FloatArray>, sampleRate: Int): ByteArray {
        require(channels.isNotEmpty() && channels.all { it.size == channels[0].size })
        val ch = channels.size
        val frames = channels[0].size
        val data = frames * ch * 4
        val w = LittleEndian.Writer(44 + data)
        w.ascii("RIFF").i32(36 + data).ascii("WAVE")
        w.ascii("fmt ").i32(16).i16(3).i16(ch).i32(sampleRate).i32(sampleRate * ch * 4).i16(ch * 4).i16(32)
        w.ascii("data").i32(data)
        for (f in 0 until frames) for (c in 0 until ch) {
            val v = channels[c][f]
            w.i32((if (v.isFinite()) v else 0f).toBits())
        }
        return w.bytes
    }
}
