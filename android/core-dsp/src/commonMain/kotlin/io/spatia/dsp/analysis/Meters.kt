package io.spatia.dsp.analysis

import io.spatia.dsp.Db
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt

/** Peak / RMS meter plus a coarse log-spaced spectrum, computed from the processed output (no RECORD_AUDIO). */
class Meters(private val sampleRate: Int, fftSize: Int = 1024) {
    private val fft = Fft(fftSize)
    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2 * PI * it / (fftSize - 1)) }
    @kotlin.concurrent.Volatile var peakDb = Db.SILENCE_DB; private set
    @kotlin.concurrent.Volatile var rmsDb = Db.SILENCE_DB; private set

    fun push(buf: FloatArray, frames: Int, channels: Int) {
        var peak = 0f; var sum = 0.0
        var idx = 0
        for (f in 0 until frames) {
            var m = 0f
            for (c in 0 until channels) { val v = buf[idx + c]; peak = maxOf(peak, abs(v)); m += v; sum += (v * v).toDouble() }
            ring[ringPos] = m / channels; ringPos = (ringPos + 1) % ring.size
            idx += channels
        }
        peakDb = Db.fromLinear(peak)
        rmsDb = if (frames == 0) Db.SILENCE_DB else Db.fromLinear(sqrt(sum / (frames * channels)).toFloat())
    }

    /** [bands] log-spaced band levels in dB between 20 Hz and Nyquist. Call from the UI thread, it allocates. */
    fun spectrum(bands: Int = 24): FloatArray {
        val n = ring.size
        val re = DoubleArray(n) { ring[(ringPos + it) % n] * window[it] }
        val im = DoubleArray(n)
        fft.transform(re, im)
        val out = FloatArray(bands)
        val nyq = sampleRate / 2.0
        for (b in 0 until bands) {
            val lo = 20.0 * (nyq / 20.0).pow(b.toDouble() / bands)
            val hi = 20.0 * (nyq / 20.0).pow((b + 1.0) / bands)
            val k0 = (lo / nyq * n / 2).toInt().coerceIn(1, n / 2 - 1)
            val k1 = (hi / nyq * n / 2).toInt().coerceIn(k0 + 1, n / 2)
            var e = 0.0
            for (k in k0 until k1) e += re[k] * re[k] + im[k] * im[k]
            out[b] = Db.fromLinear((sqrt(e / (k1 - k0)) * 4 / n).toFloat())
        }
        return out
    }
}
