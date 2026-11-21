package io.spatia.dsp

import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.toI3dl2
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentI3dl2Test {
    private val rooms = Environment.entries.filter { it != Environment.OFF }

    @Test fun allValuesWithinPlatformRanges() {
        for (env in rooms) for (k in 1..10) {
            val p = env.preset!!.toI3dl2(k / 10f)!!
            assertTrue(p.roomLevel in -9000..0 && p.roomHFLevel in -9000..0)
            assertTrue(p.decayTime in 100..20000 && p.decayHFRatio in 100..2000)
            assertTrue(p.reflectionsLevel in -9000..1000 && p.reflectionsDelay in 0..300)
            assertTrue(p.reverbLevel in -9000..2000 && p.reverbDelay in 0..100)
            assertTrue(p.diffusion in 0..1000 && p.density in 0..1000)
        }
    }

    @Test fun zeroIntensityDisablesAndMeterIsMonotonic() {
        for (env in rooms) {
            assertNull(env.preset!!.toI3dl2(0f))
            var prev = env.preset!!.toI3dl2(0.05f)!!
            for (k in 2..20) {
                val p = env.preset!!.toI3dl2(k / 20f)!!
                assertTrue("$env", p.roomLevel + p.reverbLevel >= prev.roomLevel + prev.reverbLevel)
                assertTrue("$env", p.decayTime >= prev.decayTime)
                prev = p
            }
        }
    }

    @Test fun spacesKeepTheirCharacter() {
        fun p(e: Environment) = e.preset!!.toI3dl2(0.5f)!!
        assertTrue(p(Environment.CATHEDRAL).decayTime > p(Environment.HALL).decayTime)
        assertTrue(p(Environment.HALL).decayTime > p(Environment.BATHROOM).decayTime)
        assertTrue(p(Environment.ECHOING).reflectionsDelay > p(Environment.HALL).reflectionsDelay)
        assertTrue(p(Environment.CINEMA).roomHFLevel < p(Environment.BATHROOM).roomHFLevel) // vintage = darker
        assertTrue(p(Environment.HALL).roomLevel == 0) // default intensity = full designed level
    }
}
