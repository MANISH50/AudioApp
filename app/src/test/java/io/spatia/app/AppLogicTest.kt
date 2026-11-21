package io.spatia.app

import android.media.AudioDeviceInfo
import io.spatia.app.data.AppState
import io.spatia.app.data.SettingsRepository
import io.spatia.app.output.DeviceMapping
import io.spatia.app.player.FormatSupport
import io.spatia.app.player.FormatSupport.Verdict
import io.spatia.app.player.ReplayGain
import io.spatia.app.spatial.HeadTrackerSource
import io.spatia.app.system.SessionEffectsManager
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {
    @Test fun formatClassification() {
        for (ok in listOf("a.flac" to "audio/flac", "a.opus" to null, "a.m4a" to "audio/mp4", "a.mp3" to "audio/mpeg", "a.wav" to "audio/x-wav")) {
            assertEquals(Verdict.Supported, FormatSupport.classify(ok.second, ok.first, platformIamfDecoder = false))
        }
        assertEquals(Verdict.Unsupported(R.string.format_iamf_raw), FormatSupport.classify(null, "x.iamf", true))
        assertEquals(Verdict.Unsupported(R.string.format_iamf_no_decoder), FormatSupport.classify("audio/iamf", "x.mp4", false))
        assertEquals(Verdict.Supported, FormatSupport.classify("audio/iamf", "x.mp4", true))
        assertEquals(Verdict.Unsupported(R.string.format_known_unsupported), FormatSupport.classify(null, "x.dsf", false))
        assertEquals(Verdict.Unsupported(R.string.format_not_audio), FormatSupport.classify("image/png", "x.png", false))
        assertEquals(Verdict.Unsupported(R.string.format_unknown), FormatSupport.classify(null, "x.zzz", false))
    }

    @Test fun replayGainParsing() {
        assertEquals(-6.5f, ReplayGain.gainDb(mapOf("REPLAYGAIN_TRACK_GAIN" to "-6.50 dB"))!!, 1e-4f)
        assertEquals(2f, ReplayGain.gainDb(mapOf("REPLAYGAIN_ALBUM_GAIN" to "+2 dB"))!!, 1e-4f)
        // R128: -512/256 = -2 dB relative to -23 LUFS -> +3 dB at the ReplayGain reference.
        assertEquals(3f, ReplayGain.gainDb(mapOf("R128_TRACK_GAIN" to "-512"))!!, 1e-4f)
        assertEquals(15f, ReplayGain.gainDb(mapOf("REPLAYGAIN_TRACK_GAIN" to "99 dB"))!!, 0f)
        assertNull(ReplayGain.gainDb(mapOf("REPLAYGAIN_TRACK_GAIN" to "loud; DROP TABLE")))
        assertNull(ReplayGain.gainDb(emptyMap()))
    }

    @Test fun deviceMapping() {
        assertEquals(OutputType.SPEAKER, DeviceMapping.map(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(OutputType.WIRED, DeviceMapping.map(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputType.BLUETOOTH, DeviceMapping.map(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputType.BLUETOOTH, DeviceMapping.map(DeviceMapping.TYPE_BLE_HEADSET))
        assertEquals(OutputType.USB, DeviceMapping.map(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputType.HDMI_CAST, DeviceMapping.map(AudioDeviceInfo.TYPE_HDMI))
        assertNull(DeviceMapping.map(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }

    @Test fun deviceKeyIsStableAndShort() {
        val a = DeviceMapping.deviceKey(8, "Buds")
        assertEquals(a, DeviceMapping.deviceKey(8, "Buds"))
        assertNotEquals(a, DeviceMapping.deviceKey(8, "Other"))
        assertEquals(16, a.length)
    }

    @Test fun settingsRoundTripAndCorruptionFallback() {
        val state = AppState(perType = mapOf(OutputType.WIRED to SoundSettings(bassDb = 4f)), appBoostDb = mapOf("com.example.player" to 3f))
        val json = SettingsRepository.JSON.encodeToString(AppState.serializer(), state)
        assertEquals(state, SettingsRepository.decode(json))
        assertEquals(AppState(), SettingsRepository.decode("{not json"))
        assertEquals(AppState(), SettingsRepository.decode("x".repeat(600_000)))
    }

    @Test fun settingsFromVersion010StillLoadWithEnvironmentDefaults() {
        val old = """{"perType":{"WIRED":{"bassDb":4.0}}}"""
        val s = SettingsRepository.decode(old).perType.getValue(OutputType.WIRED)
        assertEquals(4f, s.bassDb)
        assertEquals(io.spatia.dsp.model.Environment.OFF, s.environment)
        assertEquals(0.5f, s.environmentIntensity)
        // A future/unknown environment name falls back to OFF instead of wiping all settings.
        val future = """{"perType":{"WIRED":{"bassDb":4.0,"environment":"SPACESHIP"}}}"""
        val f = SettingsRepository.decode(future).perType.getValue(OutputType.WIRED)
        assertEquals(4f, f.bassDb)
        assertEquals(io.spatia.dsp.model.Environment.OFF, f.environment)
    }

    @Test fun effectPanelRequestValidation() {
        val a = android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL
        assertEquals(io.spatia.app.system.EffectPanelRequest(42, "com.apple.android.music"),
            io.spatia.app.system.EffectPanelRequest.parse(a, 42, "com.apple.android.music"))
        assertNull(io.spatia.app.system.EffectPanelRequest.parse(a, 0, "com.x.y"))       // global mix not allowed
        assertNull(io.spatia.app.system.EffectPanelRequest.parse(a, -4, "com.x.y"))
        assertNull(io.spatia.app.system.EffectPanelRequest.parse("android.intent.action.VIEW", 42, "com.x.y"))
        assertEquals("unknown", io.spatia.app.system.EffectPanelRequest.parse(a, 7, "../evil")!!.packageName)
        assertEquals("unknown", io.spatia.app.system.EffectPanelRequest.parse(a, 7, null)!!.packageName)
    }

    @Test fun intensityLabels() {
        fun t(v: Float) = io.spatia.app.ui.intensityText(v, "Subtle", "Default", "Intense")
        assertEquals("Default", t(0.5f))
        assertEquals("Subtle −100%", t(0f))
        assertEquals("Intense +50%", t(0.75f))
    }

    @Test fun appStateValidationRejectsBadPackagesAndClampsBoost() {
        val s = AppState(appBoostDb = mapOf("com.ok.app" to 50f, "../../etc" to 3f, "bad pkg" to 1f)).validated()
        assertEquals(mapOf("com.ok.app" to 12f), s.appBoostDb)
    }

    @Test fun sessionTargetsRespectBoostBudget() {
        for (out in OutputType.entries) {
            val safety = OutputSafety.forType(out)
            val t = SessionEffectsManager.computeTargets(
                SoundSettings(eqGainsDb = List(10) { 12f }, bassDb = 12f), safety, 0f, appBoostDb = 12f, hasLimiter = true,
            )
            val worst = t.bandGainsDb.max() + t.inputGainDb
            assertTrue("$out worst $worst", worst <= safety.maxBoostDb + 1e-3f)
            // Without a limiter (Equalizer fallback), no net boost at all.
            val noLim = SessionEffectsManager.computeTargets(SoundSettings(bassDb = 12f), safety, 0f, 6f, hasLimiter = false)
            assertTrue(noLim.bandGainsDb.max() + noLim.inputGainDb <= 1e-3f)
        }
    }

    @Test fun sessionRampInterpolates() {
        val a = SessionEffectsManager.computeTargets(SoundSettings(), OutputSafety.forType(OutputType.WIRED), 0f, 0f, true)
        val b = SessionEffectsManager.computeTargets(SoundSettings(eqGainsDb = List(10) { -6f }), OutputSafety.forType(OutputType.WIRED), 0f, 0f, true)
        val mid = SessionEffectsManager.interpolate(a, b, 0.5f)
        for (i in a.bandGainsDb.indices) assertEquals((a.bandGainsDb[i] + b.bandGainsDb[i]) / 2, mid.bandGainsDb[i], 1e-4f)
    }

    @Test fun headTrackerYawSign() {
        // Rotation of +0.5 rad about Z (up) is a counter-clockwise turn, i.e. head turned left -> negative yaw.
        assertEquals(-0.5, HeadTrackerSource.yawFromRotationVector(0f, 0f, 0.5f), 1e-6)
        assertEquals(0.0, HeadTrackerSource.yawFromRotationVector(0f, 0f, 0f), 0.0)
        assertEquals(-3.0, HeadTrackerSource.wrap(Math.PI * 2 - 3.0), 1e-9)
    }
}
