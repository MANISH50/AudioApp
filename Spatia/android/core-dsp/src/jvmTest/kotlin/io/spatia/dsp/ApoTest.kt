package io.spatia.dsp

import io.spatia.dsp.apo.ApoImpulse
import io.spatia.dsp.apo.ApoPlanner
import io.spatia.dsp.filters.FilterType
import io.spatia.dsp.filters.ToneCurves
import io.spatia.dsp.io.WavReader
import io.spatia.dsp.io.WavWriter
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.SoundSettings
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApoTest {
    private val sr = ApoPlanner.SAMPLE_RATE

    @Test fun bypassIsNeutral() {
        val p = ApoPlanner.plan(Presets.forMode(Mode.MUSIC).copy(bypass = true, environment = Environment.HALL), OutputType.SPEAKER, 0f)
        assertTrue(p.bypass)
        assertTrue(p.filters.isEmpty())
        assertEquals(0f, p.preampDb)
        assertFalse(p.needsImpulse)
    }

    @Test fun filtersMatchTheInAppToneCurves() {
        val s = SoundSettings(eqGainsDb = listOf(3f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -2f), bassDb = 2f, voiceClarity = 0.5f)
        val p = ApoPlanner.plan(s, OutputType.WIRED, 0f)
        val expected = ToneCurves.graphicEq(s.eqGainsDb) + ToneCurves.tone(2f, 0f) + ToneCurves.voiceClarity(0.5f)
        assertEquals(expected.size, p.filters.size)
        expected.zip(p.filters).forEach { (e, f) ->
            assertEquals(e.type, f.type); assertEquals(e.freqHz, f.freqHz); assertEquals(e.gainDb, f.gainDb); assertEquals(e.q, f.q)
        }
        assertTrue(p.filters.any { it.type == FilterType.HIGH_PASS })
    }

    @Test fun preampLeavesNoNetBoost() {
        for (mode in Mode.entries) for (out in OutputType.entries) {
            val s = Presets.forMode(mode).copy(bassDb = 8f, trebleDb = 6f)
            val p = ApoPlanner.plan(s, out, 0f)
            val specs = ApoPlanner.filterSpecs(s.validated(), 0f)
            val net = ToneCurves.maxBoostDb(specs, sr, p.preampDb)
            assertTrue("$mode/$out net $net", net <= 0.05f)
            assertTrue(p.preampDb <= 0f)
        }
    }

    @Test fun nightIsApproximatedWithQuieterPreampAndContour() {
        val day = ApoPlanner.plan(SoundSettings(), OutputType.SPEAKER, 0f)
        val night = ApoPlanner.plan(SoundSettings(nightCompression = true), OutputType.SPEAKER, 0f)
        assertTrue(night.preampDb < day.preampDb)
        assertTrue(night.filters.isNotEmpty())
        assertTrue(ApoPlanner.APPROX_NIGHT in night.approximations)
    }

    @Test fun matricesHandleWidthMonoAndBalance() {
        val wide = ApoPlanner.preMatrix(SoundSettings(width = 1.5f))
        assertEquals(1.25f, wide[0], 1e-6f); assertEquals(-0.25f, wide[1], 1e-6f)
        assertTrue(ApoPlanner.matrixHeadroomDb(wide) < 0f)
        assertTrue(ApoPlanner.isIdentity(ApoPlanner.preMatrix(SoundSettings())))
        val safety = io.spatia.dsp.model.OutputSafety.forType(OutputType.SPEAKER)
        val mono = ApoPlanner.postMatrix(SoundSettings(mono = true, balance = 0.5f), safety)
        assertEquals(0.25f, mono[0], 1e-6f); assertEquals(0.5f, mono[2], 1e-6f)
        assertEquals(0f, ApoPlanner.matrixHeadroomDb(mono))
    }

    @Test fun impulseKeepsDryTapAndAddsTheRoom() {
        for (env in Environment.entries.filter { it != Environment.OFF }) {
            val s = SoundSettings(environment = env, environmentIntensity = 0.8f)
            assertTrue(ApoImpulse.needsImpulse(s, false))
            val ir = ApoImpulse.render(s, sr)
            assertEquals(2, ir.size)
            val seconds = ir[0].size / sr.toFloat()
            assertTrue("$env $seconds", seconds in ApoImpulse.MIN_SECONDS..ApoImpulse.MAX_SECONDS)
            for (c in ir) {
                assertEquals(1f, c[0], 0f)
                assertTrue(c.all { it.isFinite() })
                assertTrue("$env has a tail", c.drop(1).sumOf { abs(it).toDouble() } > 0.1)
                assertEquals(0f, c.last(), 1e-6f)
            }
            assertTrue(ApoImpulse.headroomDb(ir) <= 0f)
        }
        assertFalse(ApoImpulse.needsImpulse(SoundSettings(), false))
        assertTrue(ApoImpulse.needsImpulse(SoundSettings(spatialEnabled = true, roomSize = 0.3f), false))
    }

    @Test fun intensityChangesTheImpulse() {
        val a = ApoImpulse.render(SoundSettings(environment = Environment.HALL, environmentIntensity = 0.2f), sr)
        val b = ApoImpulse.render(SoundSettings(environment = Environment.HALL, environmentIntensity = 0.9f), sr)
        val ea = a[0].sumOf { (it * it).toDouble() }; val eb = b[0].sumOf { (it * it).toDouble() }
        assertTrue(eb > ea)
    }

    @Test fun floatWavRoundTrips() {
        val ir = arrayOf(floatArrayOf(1f, 0.5f, -0.25f), floatArrayOf(0f, Float.NaN, 0.125f))
        val back = WavReader.read(WavWriter.writeFloat32(ir, sr))
        assertEquals(sr, back.sampleRate)
        assertEquals(1f, back.channels[0][0]); assertEquals(-0.25f, back.channels[0][2])
        assertEquals(0f, back.channels[1][1]); assertEquals(0.125f, back.channels[1][2])
    }
}
