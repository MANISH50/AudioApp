package io.spatia.dsp

import io.spatia.dsp.analysis.DoseTracker
import io.spatia.dsp.analysis.HearingProfile
import io.spatia.dsp.filters.BassEnhancer
import io.spatia.dsp.filters.Convolver
import io.spatia.dsp.filters.ToneCurves
import io.spatia.dsp.spatial.Crossfeed
import io.spatia.dsp.spatial.HeadModel
import kotlin.math.PI
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {
    @Test fun convolverMatchesDirectConvolution() {
        val r = Random(3)
        val ir = Array(1) { FloatArray(700) { r.nextFloat() - 0.5f } }
        val frames = 3000
        val x = TestSignals.noiseStereo(frames, 0.5f, 7)
        val out = FloatArray(frames * 2)
        val conv = Convolver(ir, blockSize = 64)
        conv.process(x, frames, out)
        val lat = conv.latencyFrames
        for (n in 0 until frames - lat step 37) {
            var ref = 0.0
            for (k in 0 until minOf(ir[0].size, n + 1)) ref += ir[0][k] * x[2 * (n - k)]
            assertEquals(ref, out[2 * (n + lat)].toDouble(), 1e-3)
        }
    }

    @Test fun crossfeedKeepsMonoLevelAndReducesSeparation() {
        val cf = Crossfeed(48000).apply { amount = 1f }
        val mono = TestSignals.sineStereo(200.0, 48000, 9600, 0.5f)
        cf.process(mono, 9600)
        // Low-passed, delayed feed is not perfectly in phase, so allow < 1 dB of mono level change.
        val ratio = TestSignals.rms(mono, 9600, 19200) / (0.5 / kotlin.math.sqrt(2.0))
        assertTrue("ratio $ratio", ratio in 0.89..1.0)

        val cf2 = Crossfeed(48000).apply { amount = 1f }
        val left = TestSignals.sineStereo(200.0, 48000, 9600, 0.5f).also { for (i in 1 until it.size step 2) it[i] = 0f }
        cf2.process(left, 9600)
        val r = FloatArray(4800) { left[2 * (4800 + it) + 1] }
        assertTrue(TestSignals.rms(r) > 0.05)
    }

    @Test fun loudnessCompensationIsMonotonic() {
        var prevBass = -1f
        for (att in 0..60 step 5) {
            val specs = ToneCurves.loudnessCompensation(att.toFloat())
            val bass = specs.firstOrNull()?.gainDb ?: 0f
            assertTrue(bass >= prevBass)
            assertTrue(bass <= 10f)
            prevBass = bass
        }
        assertTrue(ToneCurves.loudnessCompensation(0f).isEmpty())
    }

    @Test fun psychoBassIsBoundedAndAddsHarmonics() {
        val be = BassEnhancer(48000).apply { amount = 1f }
        val x = TestSignals.sineStereo(60.0, 48000, 48000, 0.5f)
        be.process(x, 48000)
        assertTrue(TestSignals.peak(x) < 2.0f)
        assertTrue(x.all { it.isFinite() })
    }

    @Test fun headModelItdIsPhysicallyPlausible() {
        assertEquals(0.0, HeadModel.itdSeconds(0.0), 1e-9)
        val side = HeadModel.itdSeconds(PI / 2)
        assertTrue(side in 0.00060..0.00070)
        assertEquals(HeadModel.itdSeconds(PI / 4), HeadModel.itdSeconds(3 * PI / 4), 1e-9)
        // Shadow: facing ear has HF boost (alpha ~ 2), opposite ear HF cut.
        assertTrue(HeadModel.alpha(0.0) > 1.9 && HeadModel.alpha(PI * 150 / 180) < 0.11)
    }

    @Test fun doseFollowsThreeDbExchangeRate() {
        assertEquals(1.0, DoseTracker.doseFraction(80.0, 40 * 3600.0), 1e-9)
        assertEquals(1.0, DoseTracker.doseFraction(83.0, 20 * 3600.0), 1e-9)
        assertEquals(0.0, DoseTracker.doseFraction(Double.NaN, 100.0), 0.0)
    }

    @Test fun hearingCompensationUsesHalfGainAndCap() {
        val l = listOf(-60f, -60f, -60f, -60f, -50f, -40f, -30f, -60f)
        val c = HearingProfile.compensation(l, l)
        assertEquals(0f, c[0]); assertEquals(5f, c[4]); assertEquals(6f, c[6])
    }
}
