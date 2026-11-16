package io.spatia.dsp

import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RampAndSafetyTest {
    @Test fun rampIsMonotonicBoundedAndReachesTarget() {
        val r = ParamRamp(0f, 100)
        r.setTarget(1f)
        var prev = 0f
        repeat(100) {
            val v = r.next()
            assertTrue(v >= prev)
            assertTrue(v - prev <= 0.0101f)
            prev = v
        }
        assertEquals(1f, r.current, 0f)
        assertFalse(r.isRamping)
    }

    @Test fun rampIgnoresNonFiniteTargets() {
        val r = ParamRamp(0.5f, 10)
        r.setTarget(Float.NaN); r.setTarget(Float.POSITIVE_INFINITY)
        assertEquals(0.5f, r.next(), 0f)
    }

    @Test fun headroomKeepsNetBoostWithinOutputBudget() {
        for (t in OutputType.entries) {
            val s = OutputSafety.forType(t)
            val pre = SafetyPolicy.headroomGainDb(12f, s)
            assertEquals(s.maxBoostDb, 12f + pre, 1e-4f)
            assertEquals(0f, SafetyPolicy.headroomGainDb(s.maxBoostDb - 1f, s), 0f)
            assertEquals(0f, SafetyPolicy.headroomGainDb(-6f, s), 0f)
        }
    }

    @Test fun headphonesAreStricterThanSpeaker() {
        val spk = OutputSafety.forType(OutputType.SPEAKER)
        for (t in listOf(OutputType.WIRED, OutputType.BLUETOOTH, OutputType.USB)) {
            val hp = OutputSafety.forType(t)
            assertTrue(hp.isHeadphones)
            assertTrue(hp.maxBoostDb < spk.maxBoostDb)
            assertTrue(hp.volumeWarningFraction < spk.volumeWarningFraction)
        }
        assertTrue(SafetyPolicy.shouldWarnVolume(0.7f, OutputSafety.forType(OutputType.WIRED)))
        assertFalse(SafetyPolicy.shouldWarnVolume(0.7f, spk))
        assertTrue(SafetyPolicy.shouldWarnVolume(Float.NaN, spk))
    }

    @Test fun appBoostIsCapped() {
        val s = OutputSafety.forType(OutputType.WIRED)
        assertEquals(s.maxBoostDb, SafetyPolicy.capAppBoostDb(20f, s), 0f)
        assertEquals(0f, SafetyPolicy.capAppBoostDb(-5f, s), 0f)
        assertEquals(0f, SafetyPolicy.capAppBoostDb(Float.NaN, s), 0f)
    }
}
