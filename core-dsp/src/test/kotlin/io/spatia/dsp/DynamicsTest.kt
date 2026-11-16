package io.spatia.dsp

import io.spatia.dsp.dynamics.Compressor
import io.spatia.dsp.dynamics.LoudnessNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsTest {
    @Test fun compressorStaticCurveFollowsRatioAboveKnee() {
        val c = Compressor(2, 48000).apply { thresholdDb = -30f; ratio = 4f; kneeDb = 6f }
        assertEquals(-40f, c.staticCurveDb(-40f), 1e-4f)
        assertEquals(-30f + 20f / 4f, c.staticCurveDb(-10f), 1e-4f)
        // Continuous through the knee.
        assertEquals(c.staticCurveDb(-27f - 1e-3f), c.staticCurveDb(-27f + 1e-3f), 1e-2f)
    }

    @Test fun compressorReducesDynamicRange() {
        val c = Compressor(2, 48000).apply { makeupDb = 0f }
        val loud = TestSignals.sineStereo(200.0, 48000, 24000, 0.9f)
        val quiet = TestSignals.sineStereo(200.0, 48000, 24000, 0.01f)
        c.process(loud, 24000); val loudOut = TestSignals.rms(loud, 24000, 48000)
        c.reset(); c.process(quiet, 24000); val quietOut = TestSignals.rms(quiet, 24000, 48000)
        val inRange = 20 * kotlin.math.log10(0.9 / 0.01)
        val outRange = 20 * kotlin.math.log10(loudOut / quietOut)
        assertTrue("out range $outRange", outRange < inRange - 15)
    }

    @Test fun normalizerRaisesQuietMaterialButNotPastCap() {
        val n = LoudnessNormalizer(2, 48000).apply { maxGainDb = 6f; targetLufs = -16f }
        val sig = TestSignals.sineStereo(1000.0, 48000, 48000 * 30, 0.02f) // ~ -34 LUFS, far below target
        n.process(sig, 48000 * 30)
        assertEquals(6f, n.currentGainDb, 0.01f)
    }

    @Test fun normalizerDoesNotBoostSilence() {
        val n = LoudnessNormalizer(2, 48000)
        val sig = FloatArray(48000 * 2 * 5)
        n.process(sig, 48000 * 5)
        assertTrue(n.currentGainDb <= 0f)
    }
}
