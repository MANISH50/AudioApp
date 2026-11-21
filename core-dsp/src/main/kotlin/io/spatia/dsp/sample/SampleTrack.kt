package io.spatia.dsp.sample

import io.spatia.dsp.Db
import io.spatia.dsp.filters.Biquad
import io.spatia.dsp.filters.BiquadCoeffs
import io.spatia.dsp.filters.FilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Spatia's own demo track, synthesised in code (no third-party audio, nothing to license, nothing in the APK).
 * Each section is designed to make a group of features easy to hear:
 *  - 0–8 s    groove (kick, snare, hi-hat, bass, chords): modes, EQ, bass/treble, loudness
 *  - 8–14 s   voice-like melody over a soft pad: voice clarity, Podcast/Voice and Night modes
 *  - 14–20 s  short claps and plucks separated by silence: environment tails and echoes
 *  - 20–24 s  percussion sweeping left and right: width, spatial 3D, crossfeed, balance
 * Deterministic and loopable; peak normalised to [PEAK_DBFS] so effects have headroom.
 */
object SampleTrack {
    const val LENGTH_S = 24.0
    const val VOICE_START_S = 8.0
    const val HITS_START_S = 14.0
    const val PAN_START_S = 20.0
    /** Onsets of the isolated hits; each hit lasts at most [HIT_LENGTH_S], followed by silence. */
    val HIT_TIMES_S = doubleArrayOf(14.0, 15.5, 17.0, 18.5)
    const val HIT_LENGTH_S = 0.35
    const val PEAK_DBFS = -6f
    private const val BPM = 120.0
    private const val BEAT = 60.0 / BPM

    fun render(sampleRate: Int = 44100): Array<FloatArray> {
        require(sampleRate in 8000..192000)
        val n = (LENGTH_S * sampleRate).toInt()
        val l = FloatArray(n); val r = FloatArray(n)
        val t = Track(sampleRate, l, r, Random(1234))
        t.groove(); t.voice(); t.hits(); t.sweep()
        t.fadeEdges()
        // Normalise so every section shares one level and leaves headroom for boosts and rooms.
        val peak = maxOf(l.maxOf { abs(it) }, r.maxOf { abs(it) })
        val g = if (peak > 0f) Db.toLinear(PEAK_DBFS) / peak else 1f
        for (i in 0 until n) { l[i] *= g; r[i] *= g }
        return arrayOf(l, r)
    }

    private class Track(val sr: Int, val l: FloatArray, val r: FloatArray, val rnd: Random) {
        fun idx(seconds: Double) = (seconds * sr).toInt()

        /** Adds a mono voice at [start] with constant-power pan (-1 left .. +1 right). */
        fun add(start: Double, samples: FloatArray, pan: Float = 0f, gain: Float = 1f) {
            val a = (pan.coerceIn(-1f, 1f) + 1f) * PI.toFloat() / 4f
            val gl = cos(a) * gain; val gr = sin(a) * gain
            val s0 = idx(start)
            for (i in samples.indices) {
                val k = s0 + i
                if (k >= l.size) break
                l[k] += samples[i] * gl; r[k] += samples[i] * gr
            }
        }

        fun env(len: Int, attack: Double, decay: Double) = FloatArray(len) { i ->
            val s = i.toDouble() / sr
            val a = if (attack > 0 && s < attack) s / attack else 1.0
            (a * exp(-s / decay)).toFloat()
        }

        fun noise(len: Int) = FloatArray(len) { rnd.nextFloat() * 2f - 1f }

        fun filter(x: FloatArray, type: FilterType, hz: Double, q: Double = 0.707): FloatArray {
            val b = Biquad(BiquadCoeffs.design(type, hz, q, 0.0, sr.toDouble()))
            return FloatArray(x.size) { b.process(x[it]) }
        }

        fun kick(): FloatArray {
            val len = idx(0.35)
            var ph = 0.0
            val e = env(len, 0.002, 0.12)
            return FloatArray(len) { i ->
                val f = 45.0 + 75.0 * exp(-i.toDouble() / sr / 0.03)
                ph += 2 * PI * f / sr
                (sin(ph) * e[i]).toFloat()
            }
        }

        fun snare(): FloatArray {
            val len = idx(0.2)
            val body = filter(noise(len), FilterType.BAND_PASS, 1800.0, 0.8)
            val e = env(len, 0.001, 0.05)
            return FloatArray(len) { i -> (body[i] * 1.6f + sin(2 * PI * 185 * i / sr).toFloat() * 0.5f) * e[i] }
        }

        fun hat(decay: Double = 0.03): FloatArray {
            val len = idx(0.08)
            val x = filter(noise(len), FilterType.HIGH_PASS, 7000.0)
            val e = env(len, 0.0005, decay)
            return FloatArray(len) { x[it] * e[it] }
        }

        fun tone(freq: Double, seconds: Double, attack: Double, decay: Double, harmonics: Int = 1, detune: Double = 0.0): FloatArray {
            val len = idx(seconds)
            val e = env(len, attack, decay)
            return FloatArray(len) { i ->
                var v = 0.0
                for (h in 1..harmonics) v += sin(2 * PI * freq * (1 + detune) * h * i / sr) / h
                (v * e[i]).toFloat()
            }
        }

        fun groove() {
            val roots = doubleArrayOf(55.0, 43.65, 65.41, 49.0)          // A, F, C, G
            val chords = arrayOf(
                doubleArrayOf(220.0, 261.63, 329.63), doubleArrayOf(174.61, 220.0, 261.63),
                doubleArrayOf(261.63, 329.63, 392.0), doubleArrayOf(196.0, 246.94, 293.66),
            )
            for (bar in 0 until 4) {
                val b0 = bar * 4 * BEAT
                for (beat in 0 until 4) {
                    val tb = b0 + beat * BEAT
                    if (beat == 0 || beat == 2) add(tb, kick(), gain = 0.9f)
                    if (beat == 1 || beat == 3) add(tb, snare(), gain = 0.5f)
                    for (e8 in 0 until 2) {
                        add(tb + e8 * BEAT / 2, hat(), pan = 0.3f, gain = 0.25f)
                        add(tb + e8 * BEAT / 2, filter(tone(roots[bar], BEAT / 2 * 0.9, 0.005, 0.12, harmonics = 5),
                            FilterType.LOW_PASS, 500.0), gain = 0.45f)
                    }
                }
                for ((k, f) in chords[bar].withIndex()) {
                    add(b0, tone(f, 4 * BEAT, 0.15, 1.5, harmonics = 2, detune = -0.002), pan = -0.5f, gain = 0.07f + 0.01f * k)
                    add(b0, tone(f, 4 * BEAT, 0.15, 1.5, harmonics = 2, detune = 0.002), pan = 0.5f, gain = 0.07f + 0.01f * k)
                }
            }
        }

        /** Formant synthesis: a buzzy pitched source shaped by three vowel resonances, with vibrato. */
        fun voice() {
            val vowels = arrayOf(doubleArrayOf(800.0, 1150.0, 2900.0), doubleArrayOf(400.0, 1600.0, 2700.0),
                doubleArrayOf(350.0, 600.0, 2400.0), doubleArrayOf(270.0, 2140.0, 2950.0))
            val melody = doubleArrayOf(220.0, 246.94, 261.63, 293.66, 261.63, 246.94)
            val noteLen = (HITS_START_S - VOICE_START_S - 0.5) / melody.size
            for ((k, f0) in melody.withIndex()) {
                val len = idx(noteLen)
                var ph = 0.0
                val src = FloatArray(len) { i ->
                    val vib = 1 + 0.012 * sin(2 * PI * 5.0 * i / sr)
                    ph += f0 * vib / sr
                    ((ph % 1.0) * 2 - 1).toFloat()
                }
                val vw = vowels[k % vowels.size]
                val out = FloatArray(len)
                for ((j, fq) in vw.withIndex()) {
                    val band = filter(src, FilterType.BAND_PASS, fq, 6.0)
                    val g = 1f / (j + 1)
                    for (i in 0 until len) out[i] += band[i] * g
                }
                val e = env(len, 0.06, noteLen * 2)
                val rel = idx(0.08)
                for (i in 0 until len) out[i] *= e[i] * (if (i > len - rel) (len - i).toFloat() / rel else 1f)
                add(VOICE_START_S + k * noteLen, out, gain = 1.4f)
            }
            for (f in doubleArrayOf(110.0, 164.81)) {
                add(VOICE_START_S, tone(f, HITS_START_S - VOICE_START_S - 0.3, 0.8, 6.0), pan = -0.3f, gain = 0.06f)
                add(VOICE_START_S, tone(f * 1.003, HITS_START_S - VOICE_START_S - 0.3, 0.8, 6.0), pan = 0.3f, gain = 0.06f)
            }
            // Release the pad before the hits section so its silences are truly silent.
            fadeOut(HITS_START_S - 0.3, HITS_START_S - 0.05)
        }

        fun hits() {
            for ((k, t0) in HIT_TIMES_S.withIndex()) {
                val hit = if (k % 2 == 0) clap() else pluck(if (k == 1) 392.0 else 523.25)
                add(t0, hit, gain = 0.9f)
            }
        }

        private fun clap(): FloatArray {
            val len = idx(HIT_LENGTH_S)
            val x = filter(noise(len), FilterType.BAND_PASS, 1200.0, 0.9)
            return FloatArray(len) { i ->
                val s = i.toDouble() / sr
                // Three quick bursts then a short decay, like hands clapping.
                val burst = (0 until 3).sumOf { b -> val d = s - b * 0.011; if (d >= 0) exp(-d / 0.006) else 0.0 }
                val tail = if (s > 0.03) exp(-(s - 0.03) / 0.04) else 0.0
                val cut = if (s > HIT_LENGTH_S - 0.02) (HIT_LENGTH_S - s) / 0.02 else 1.0
                (x[i] * (burst * 0.6 + tail) * cut * 1.8).toFloat()
            }
        }

        private fun pluck(freq: Double): FloatArray {
            val len = idx(HIT_LENGTH_S)
            val x = tone(freq, HIT_LENGTH_S, 0.002, 0.07, harmonics = 6)
            val rel = idx(0.02)
            for (i in len - rel until len) x[i] *= (len - i).toFloat() / rel
            return x
        }

        fun sweep() {
            val steps = ((LENGTH_S - PAN_START_S - 0.25) / (BEAT / 2)).toInt()
            for (k in 0 until steps) {
                val t0 = PAN_START_S + k * BEAT / 2
                val pan = sin(2 * PI * (t0 - PAN_START_S) / 2.0).toFloat() * 0.95f
                add(t0, hat(0.05), pan = pan, gain = 0.6f)
                add(t0, filter(tone(880.0, 0.12, 0.001, 0.03, harmonics = 3), FilterType.HIGH_PASS, 400.0), pan = pan, gain = 0.35f)
            }
        }

        fun fadeOut(from: Double, to: Double) {
            val a = idx(from); val b = idx(to)
            for (i in a until l.size) {
                val g = if (i >= b) 0f else 1f - (i - a).toFloat() / (b - a)
                l[i] *= g; r[i] *= g
                if (i >= b) break
            }
            // Everything after `to` up to the hits section is zeroed by the loop above only at one sample; clear it.
            for (i in b until idx(HITS_START_S)) { l[i] = 0f; r[i] = 0f }
        }

        /** 10 ms fades at both ends so the loop point never clicks. */
        fun fadeEdges() {
            val f = idx(0.01)
            for (i in 0 until f) {
                val g = (i.toFloat() / f).pow(2)
                l[i] *= g; r[i] *= g
                l[l.size - 1 - i] *= g; r[r.size - 1 - i] *= g
            }
        }
    }
}
