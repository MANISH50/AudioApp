package io.spatia.dsp

import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.EnvironmentPreset
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import io.spatia.dsp.spatial.EnvironmentReverb
import kotlin.math.abs
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentTest {
    private val sr = 48000
    private val rooms = Environment.entries.filter { it != Environment.OFF }

    private fun run(env: Environment, intensity: Float, input: FloatArray, frames: Int): Pair<FloatArray, FloatArray> {
        val r = EnvironmentReverb(sr)
        r.setPreset(env.preset, intensity)
        val out = input.copyOf()
        val wet = FloatArray(frames * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(256, frames - done)
            val block = out.copyOfRange(done * 2, (done + n) * 2)
            r.process(block, n)
            block.copyInto(out, done * 2)
            for (f in 0 until n) { wet[2 * (done + f)] = r.lastWetL[f]; wet[2 * (done + f) + 1] = r.lastWetR[f] }
            done += n
        }
        return out to wet
    }

    private fun impulse(frames: Int) = FloatArray(frames * 2).also { it[0] = 0.5f; it[1] = 0.5f }

    @Test fun everyPresetExistsExceptOff() {
        assertEquals(null, Environment.OFF.preset)
        rooms.forEach { assertTrue("$it", it.preset != null) }
    }

    @Test fun originalAudioPassesThroughUntouched() {
        val x = TestSignals.noiseStereo(sr, 0.3f)
        for (env in rooms) {
            val (out, wet) = run(env, 0.5f, x, sr)
            for (i in x.indices) assertEquals("$env sample $i", x[i], out[i] - wet[i], 1e-5f)
        }
    }

    @Test fun zeroIntensityIsSilentEffect() {
        val x = TestSignals.noiseStereo(sr / 2, 0.3f)
        for (env in rooms) {
            val (out, _) = run(env, 0f, x, sr / 2)
            for (i in x.indices) assertEquals(x[i], out[i], 1e-6f)
        }
    }

    @Test fun intensityMeterIsMonotonicWithDefaultInTheMiddle() {
        for (env in rooms) {
            val p = env.preset!!
            assertEquals(1f, p.scaled(0.5f).wetGain, 0f)
            var prevWet = -1f; var prevRt = 0f
            for (k in 0..10) {
                val s = p.scaled(k / 10f)
                assertTrue(s.wetGain >= prevWet && s.rt60 >= prevRt)
                assertTrue(s.echoFeedback <= EnvironmentPreset.MAX_ECHO_FEEDBACK)
                prevWet = s.wetGain; prevRt = s.rt60
            }
            assertEquals(2f, p.scaled(1f).wetGain, 1e-6f)
            assertEquals(p.rt60, p.scaled(0.5f).rt60, 1e-6f)
        }
    }

    /** Time for the impulse tail energy (Schroeder integration) to fall from -5 dB to -25 dB, extrapolated to 60 dB. */
    private fun measureRt60(wet: FloatArray, frames: Int): Double {
        val e = DoubleArray(frames)
        var acc = 0.0
        for (f in frames - 1 downTo 0) { acc += wet[2 * f] * wet[2 * f] + wet[2 * f + 1] * wet[2 * f + 1]; e[f] = acc }
        val total = e[0]
        fun t(db: Double) = e.indexOfFirst { 10 * log10(it / total) <= db }
        return (t(-25.0) - t(-5.0)) * 3.0 / sr
    }

    @Test fun reverbTimesMatchTheSpaces() {
        val frames = sr * 8
        fun rt(env: Environment, i: Float = 0.5f) = measureRt60(run(env, i, impulse(frames), frames).second, frames)
        val hall = rt(Environment.HALL)
        assertTrue("hall $hall", hall in 1.2..3.6)
        assertTrue(rt(Environment.CATHEDRAL) > hall)
        assertTrue(hall > rt(Environment.BATHROOM))
        assertTrue(rt(Environment.BATHROOM) > rt(Environment.LIVING_ROOM))
        assertTrue(rt(Environment.HALL, 1f) > hall * 1.2)
        assertTrue(rt(Environment.HALL, 0.1f) < hall)
    }

    @Test fun echoingRepeatsAtTheEchoTimeAndDiesAway() {
        val frames = sr * 6
        val (_, wet) = run(Environment.ECHOING, 0.5f, impulse(frames), frames)
        val p = Environment.ECHOING.preset!!
        val at = ((p.preDelayMs + p.echoMs) / 1000f * sr).toInt()
        fun peak(from: Int, to: Int) = (from until to).maxOf { abs(wet[2 * it]) }
        val echo = peak(at - 200, at + 200)
        val before = peak(at - 2400, at - 600)
        assertTrue("echo $echo vs before $before", echo > before * 2)
        assertTrue(peak(frames - sr / 2, frames) < echo * 0.01f)
    }

    @Test fun chainStaysSafeAndClickFreeAcrossEnvironments() {
        val chain = DspChain(sr, 2)
        val frames = sr / 4
        var prev = 0f; var maxJump = 0f
        val ceiling = Db.toLinear(OutputSafety.forType(OutputType.WIRED).limiterCeilingDb)
        for (env in Environment.entries + Environment.HALL) {
            chain.setSettings(SoundSettings(environment = env, environmentIntensity = 1f), OutputType.WIRED)
            val x = TestSignals.sineStereo(100.0, sr, frames, 0.3f)
            val y = FloatArray(x.size)
            chain.process(x, y, frames)
            assertTrue(y.all { it.isFinite() && abs(it) <= ceiling + 1e-6f })
            for (f in 0 until frames) { maxJump = maxOf(maxJump, abs(y[2 * f] - prev)); prev = y[2 * f] }
        }
        assertTrue("max jump $maxJump", maxJump < 0.15f)

        val loud = DspChain(sr, 2)
        loud.setSettings(SoundSettings(environment = Environment.CATHEDRAL, environmentIntensity = 1f, eqGainsDb = List(10) { 12f }), OutputType.SPEAKER)
        val n = TestSignals.noiseStereo(sr * 2, 1f)
        val y = FloatArray(n.size)
        loud.process(n, y, sr * 2)
        assertTrue(TestSignals.peak(y) <= Db.toLinear(-1f) + 1e-6f)
    }

    @Test fun settingsValidateIntensity() {
        assertEquals(0.5f, SoundSettings(environmentIntensity = Float.NaN).validated().environmentIntensity)
        assertEquals(1f, SoundSettings(environmentIntensity = 9f).validated().environmentIntensity)
    }
}
