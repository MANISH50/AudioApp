package io.spatia.dsp

import io.spatia.dsp.dynamics.Limiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiterTest {
    private val sr = 48000

    @Test fun neverExceedsCeilingOnLoudNoise() {
        val lim = Limiter(2, sr).apply { ceilingDb = -1f }
        val ceiling = Db.toLinear(-1f)
        repeat(5) { seed ->
            val buf = TestSignals.noiseStereo(20000, 8f, seed)
            lim.process(buf, 20000)
            assertTrue("peak ${TestSignals.peak(buf)}", TestSignals.peak(buf) <= ceiling + 1e-6f)
        }
    }

    @Test fun neverExceedsCeilingOnIsolatedImpulses() {
        val lim = Limiter(2, sr).apply { ceilingDb = -3f }
        val buf = FloatArray(2 * 10000)
        for (i in 0 until 10000 step 997) { buf[2 * i] = 20f; buf[2 * i + 1] = -15f }
        lim.process(buf, 10000)
        assertTrue(TestSignals.peak(buf) <= Db.toLinear(-3f) + 1e-6f)
        // Look-ahead means the gain is already down when the impulse arrives: no hard clamping needed.
        assertTrue(TestSignals.peak(buf) > Db.toLinear(-3.5f))
    }

    @Test fun quietSignalPassesUnchangedApartFromLatency() {
        val lim = Limiter(2, sr)
        val x = TestSignals.sineStereo(440.0, sr, 4800, 0.3f)
        val y = x.copyOf()
        lim.process(y, 4800)
        val lat = lim.latencyFrames
        for (f in lat until 4800) assertEquals(x[2 * (f - lat)], y[2 * f], 1e-6f)
    }

    @Test fun gainRecoversAfterTransient() {
        val lim = Limiter(2, sr)
        val loud = TestSignals.sineStereo(440.0, sr, 4800, 4f)
        lim.process(loud, 4800)
        assertTrue(lim.gainReductionDb < -10f)
        val quiet = TestSignals.sineStereo(440.0, sr, 48000, 0.1f)
        lim.process(quiet, 48000)
        assertTrue(lim.gainReductionDb > -0.1f)
    }
}
