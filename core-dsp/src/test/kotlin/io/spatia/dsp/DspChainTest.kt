package io.spatia.dsp

import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.SoundSettings
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DspChainTest {
    private val sr = 48000
    private val extreme = SoundSettings(
        mode = Mode.CUSTOM, eqGainsDb = List(10) { 12f }, bassDb = 12f, trebleDb = 12f, preampDb = 6f,
        loudnessNormalization = true, voiceClarity = 1f, spatialEnabled = true, width = 2f, roomSize = 1f,
        psychoBass = 1f, crossfeed = 1f, surroundUpmix = true, loudnessCompensation = true,
    )

    @Test fun outputNeverExceedsCeilingForAnyOutputWithExtremeSettings() {
        for (out in OutputType.entries) {
            val chain = DspChain(sr, 2)
            chain.setSettings(extreme, out, volumeAttenuationDb = 40f)
            val x = TestSignals.noiseStereo(sr * 2, 1f, out.ordinal)
            val y = FloatArray(x.size)
            chain.process(x, y, sr * 2)
            val ceiling = Db.toLinear(OutputSafety.forType(out).limiterCeilingDb)
            assertTrue("$out peak ${TestSignals.peak(y)}", TestSignals.peak(y) <= ceiling + 1e-6f)
            assertTrue(y.all { it.isFinite() })
        }
    }

    @Test fun neutralSettingsAreTransparent() {
        val chain = DspChain(sr, 2)
        chain.setSettings(SoundSettings(), OutputType.WIRED)
        val x = TestSignals.sineStereo(1000.0, sr, sr, 0.25f)
        val y = FloatArray(x.size)
        chain.process(x, y, sr)
        assertEquals(TestSignals.rms(x, sr, 2 * sr), TestSignals.rms(y, sr, 2 * sr), 0.002)
    }

    @Test fun modeSwitchesDoNotClick() {
        val chain = DspChain(sr, 2)
        chain.setSettings(Presets.forMode(Mode.MUSIC), OutputType.WIRED)
        val frames = sr / 4
        var prev = 0f
        var maxJump = 0f
        for (mode in listOf(Mode.MOVIE, Mode.NIGHT, Mode.VOICE, Mode.GAMING, Mode.MUSIC)) {
            val x = TestSignals.sineStereo(100.0, sr, frames, 0.3f)
            val y = FloatArray(x.size)
            chain.process(x, y, frames)
            for (f in 0 until frames) { maxJump = maxOf(maxJump, abs(y[2 * f] - prev)); prev = y[2 * f] }
            chain.setSettings(Presets.forMode(mode), OutputType.WIRED)
        }
        // A 100 Hz sine at <=1.0 amplitude moves at most ~0.013 per sample; allow generous margin.
        assertTrue("max jump $maxJump", maxJump < 0.1f)
    }

    @Test fun surroundInputIsRenderedToStereo() {
        for (ch in listOf(6, 8)) {
            val chain = DspChain(sr, ch)
            chain.setSettings(SoundSettings(spatialEnabled = true), OutputType.WIRED)
            val x = FloatArray(sr * ch) { if (it % ch == 2) 0.3f else 0f } // centre only
            val y = FloatArray(sr * 2)
            chain.process(x, y, sr)
            assertTrue(TestSignals.rms(y, sr, 2 * sr) > 0.01)
        }
    }

    @Test fun headTrackingRotatesTheImage() {
        fun leftRightRatio(yaw: Double): Double {
            val chain = DspChain(sr, 1)
            chain.setSettings(SoundSettings(spatialEnabled = true, headTracking = true), OutputType.WIRED)
            chain.setHeadYaw(yaw)
            val x = FloatArray(sr) { kotlin.math.sin(2 * Math.PI * 3000 * it / sr).toFloat() * 0.3f }
            val y = FloatArray(sr * 2)
            chain.process(x, y, sr)
            val l = FloatArray(sr / 2) { y[2 * (sr / 2 + it)] }; val r = FloatArray(sr / 2) { y[2 * (sr / 2 + it) + 1] }
            return TestSignals.rms(l) / TestSignals.rms(r)
        }
        // Mono source sits at the front; turning the head right should push it to the left ear.
        assertTrue(leftRightRatio(Math.toRadians(60.0)) > leftRightRatio(0.0) * 1.2)
    }

    @Test fun bypassIsLoudnessMatched() {
        val s = SoundSettings(eqGainsDb = List(10) { -6f })
        val chain = DspChain(sr, 2)
        chain.setSettings(s, OutputType.SPEAKER)
        val frames = sr * 2
        val x = TestSignals.noiseStereo(frames, 0.2f)
        val y = FloatArray(x.size)
        chain.process(x, y, frames)
        val processed = TestSignals.rms(y, frames, 2 * frames)
        chain.setSettings(s.copy(bypass = true), OutputType.SPEAKER)
        chain.process(x, y, frames)
        val bypassed = TestSignals.rms(y, frames, 2 * frames)
        assertEquals(processed, bypassed, processed * 0.15)
    }

    @Test fun rejectsUnsupportedLayouts() {
        assertThrows(IllegalArgumentException::class.java) { DspChain(sr, 3) }
        assertThrows(IllegalArgumentException::class.java) { DspChain(1000, 2) }
    }
}
