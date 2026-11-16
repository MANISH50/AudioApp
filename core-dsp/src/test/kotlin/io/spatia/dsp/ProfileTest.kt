package io.spatia.dsp

import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.ProfileSelector
import io.spatia.dsp.model.SoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    @Test fun mostPersonalOutputWins() {
        assertEquals(OutputType.SPEAKER, ProfileSelector.activeOutput(emptyList(), null))
        assertEquals(OutputType.BLUETOOTH, ProfileSelector.activeOutput(listOf(OutputType.SPEAKER, OutputType.BLUETOOTH), null))
        assertEquals(OutputType.WIRED, ProfileSelector.activeOutput(listOf(OutputType.BLUETOOTH, OutputType.WIRED, OutputType.SPEAKER), null))
        assertEquals(OutputType.USB, ProfileSelector.activeOutput(OutputType.entries, null))
    }

    @Test fun manualOverrideWins() {
        assertEquals(OutputType.HDMI_CAST, ProfileSelector.activeOutput(listOf(OutputType.WIRED), OutputType.HDMI_CAST))
    }

    @Test fun perDeviceThenPerTypeThenDefault() {
        val dev = SoundSettings(bassDb = 3f)
        val type = SoundSettings(bassDb = 1f)
        assertEquals(3f, ProfileSelector.settingsFor(OutputType.BLUETOOTH, "k", mapOf("k" to dev), mapOf(OutputType.BLUETOOTH to type)).bassDb)
        assertEquals(1f, ProfileSelector.settingsFor(OutputType.BLUETOOTH, "other", mapOf("k" to dev), mapOf(OutputType.BLUETOOTH to type)).bassDb)
        assertEquals(ProfileSelector.defaultFor(OutputType.SPEAKER), ProfileSelector.settingsFor(OutputType.SPEAKER, null, emptyMap(), emptyMap()))
    }

    @Test fun storedSettingsAreValidated() {
        val bad = SoundSettings(bassDb = 99f, width = Float.NaN, eqGainsDb = listOf(50f))
        val s = ProfileSelector.settingsFor(OutputType.WIRED, null, emptyMap(), mapOf(OutputType.WIRED to bad))
        assertEquals(12f, s.bassDb)
        assertEquals(1f, s.width)
        assertEquals(10, s.eqGainsDb.size)
        assertEquals(12f, s.eqGainsDb[0])
    }

    @Test fun speakerDefaultAvoidsDeepBassBoost() {
        val s = ProfileSelector.defaultFor(OutputType.SPEAKER)
        assertTrue(s.eqGainsDb[0] <= 0f && s.psychoBass > 0f)
    }

    @Test fun everyPresetIsValidAndKeepsItsMode() {
        for (m in Mode.entries) {
            val p = Presets.forMode(m)
            assertEquals(m, p.mode)
            assertEquals(p, p.validated())
        }
        assertTrue(Presets.forMode(Mode.NIGHT).nightCompression)
        assertTrue(Presets.forMode(Mode.VOICE).voiceClarity > 0.5f)
    }
}
