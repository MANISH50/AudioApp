package io.spatia.dsp.io


/**
 * Minimal, defensive RIFF/WAVE reader for impulse responses: PCM 16/24/32-bit or IEEE float 32-bit,
 * 1-2 channels, 8-192 kHz, at most [MAX_SECONDS] seconds. Everything else is rejected with a clear message.
 */
object WavReader {
    const val MAX_SECONDS = 2.0
    const val MAX_BYTES = 192_000 * 2 * 4 * 2 + 4096

    data class Wav(val sampleRate: Int, val channels: Array<FloatArray>)

    class WavException(message: String) : Exception(message)

    fun read(bytes: ByteArray): Wav {
        if (bytes.size > MAX_BYTES) throw WavException("Impulse response file is too large")
        if (bytes.size < 44) throw WavException("File too small to be a WAV")
        val le = LittleEndian
        if (le.ascii(bytes, 0, 4) != "RIFF" || le.ascii(bytes, 8, 4) != "WAVE") {
            throw WavException("Not a RIFF/WAVE file")
        }
        var pos = 12
        var format = -1; var channels = 0; var rate = 0; var bits = 0
        var dataOff = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = le.ascii(bytes, pos, 4)
            val len = le.i32(bytes, pos + 4)
            if (len < 0 || pos + 8L + len > bytes.size) throw WavException("Corrupt chunk '$id'")
            when (id) {
                "fmt " -> {
                    if (len < 16) throw WavException("Corrupt fmt chunk")
                    format = le.u16(bytes, pos + 8)
                    channels = le.i16(bytes, pos + 10)
                    rate = le.i32(bytes, pos + 12)
                    bits = le.i16(bytes, pos + 22)
                    if (format == 0xFFFE && len >= 26) format = le.u16(bytes, pos + 32)
                }
                "data" -> { dataOff = pos + 8; dataLen = len }
            }
            pos += 8 + len + (len and 1)
        }
        if (format == -1 || dataOff < 0) throw WavException("Missing fmt or data chunk")
        if (channels !in 1..2) throw WavException("Only mono or stereo impulse responses are supported")
        if (rate !in 8000..192000) throw WavException("Unsupported sample rate $rate Hz")
        val isFloat = format == 3
        if (!(format == 1 && bits in intArrayOf(16, 24, 32)) && !(isFloat && bits == 32)) {
            throw WavException("Unsupported WAV encoding (use 16/24/32-bit PCM or 32-bit float)")
        }
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        if (frames == 0) throw WavException("Impulse response is empty")
        if (frames > rate * MAX_SECONDS) throw WavException("Impulse response longer than ${MAX_SECONDS.toInt()} s")
        val out = Array(channels) { FloatArray(frames) }
        var p = dataOff
        for (f in 0 until frames) for (c in 0 until channels) {
            out[c][f] = when {
                isFloat -> le.f32(bytes, p).let { if (it.isFinite()) it.coerceIn(-4f, 4f) else 0f }
                bits == 16 -> le.i16(bytes, p) / 32768f
                bits == 24 -> ((bytes[p].toInt() and 0xFF) or ((bytes[p + 1].toInt() and 0xFF) shl 8) or (bytes[p + 2].toInt() shl 16)) / 8388608f
                else -> (le.i32(bytes, p) / 2147483648.0).toFloat()
            }
            p += bytesPer
        }
        return Wav(rate, out)
    }

    /** Peak-normalise so the IR's energy doesn't blow up the wet level, and resample naively if rates differ. */
    fun prepareImpulse(wav: Wav, targetRate: Int): Array<FloatArray> {
        val ratio = wav.sampleRate.toDouble() / targetRate
        val res = wav.channels.map { ch ->
            if (wav.sampleRate == targetRate) ch.copyOf() else {
                val n = (ch.size / ratio).toInt().coerceAtLeast(1)
                FloatArray(n) { i ->
                    val x = i * ratio; val i0 = x.toInt().coerceAtMost(ch.size - 1); val i1 = (i0 + 1).coerceAtMost(ch.size - 1)
                    (ch[i0] + (ch[i1] - ch[i0]) * (x - i0)).toFloat()
                }
            }
        }
        val energy = res.maxOf { c -> kotlin.math.sqrt(c.sumOf { (it * it).toDouble() }) }.toFloat()
        val g = if (energy > 1e-9f) 1f / energy else 0f
        return res.map { c -> FloatArray(c.size) { c[it] * g } }.toTypedArray()
    }
}
