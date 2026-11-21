package io.spatia.dsp.io

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Writes 16-bit PCM RIFF/WAVE. Counterpart of [WavReader]; used for Spatia's generated sample track. */
object WavWriter {
    fun write16(channels: Array<FloatArray>, sampleRate: Int): ByteArray {
        require(channels.isNotEmpty() && channels.all { it.size == channels[0].size })
        val ch = channels.size
        val frames = channels[0].size
        val data = frames * ch * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + data).put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(ch.toShort()).putInt(sampleRate)
            .putInt(sampleRate * ch * 2).putShort((ch * 2).toShort()).putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)).putInt(data)
        for (f in 0 until frames) for (c in 0 until ch) {
            bb.putShort((channels[c][f] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        return bb.array()
    }
}
