package io.spatia.app

import android.media.AudioDeviceInfo
import io.spatia.app.data.AppState
import io.spatia.app.data.SettingsRepository
import io.spatia.app.output.DeviceMapping
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

    @Test fun systemWideIsOnByDefault() {
        val s = AppState()
        assertTrue(s.systemWideEnabled && s.globalMode)
        // Settings saved by 0.4.x without these keys (defaults weren't encoded) now come up on.
        val old = SettingsRepository.decode("""{"perType":{"WIRED":{"bassDb":4.0}}}""")
        assertTrue(old.systemWideEnabled && old.globalMode)
        // An explicit "off" is respected.
        assertEquals(false, SettingsRepository.decode("""{"systemWideEnabled":false}""").systemWideEnabled)
    }

    @Test fun homeStatusReflectsTheGlobalEffect() {
        fun g(eq: io.spatia.app.system.EffectState) = io.spatia.app.system.SessionInfo(0, "global",
            io.spatia.app.system.SessionEffectKind.DYNAMICS_PROCESSING, isGlobal = true, eq = eq)
        val app = io.spatia.app.system.SessionInfo(42, "com.x.player", io.spatia.app.system.SessionEffectKind.DYNAMICS_PROCESSING,
            eq = io.spatia.app.system.EffectState.ACTIVE)
        assertEquals(io.spatia.app.ui.HomeStatus.OFF, io.spatia.app.ui.homeStatus(false, true, emptyList()))
        assertEquals(io.spatia.app.ui.HomeStatus.STARTING, io.spatia.app.ui.homeStatus(true, true, emptyList()))
        assertEquals(io.spatia.app.ui.HomeStatus.WORKING_ALL,
            io.spatia.app.ui.homeStatus(true, true, listOf(g(io.spatia.app.system.EffectState.ACTIVE))))
        assertEquals(io.spatia.app.ui.HomeStatus.TAKEN_OVER,
            io.spatia.app.ui.homeStatus(true, true, listOf(g(io.spatia.app.system.EffectState.TAKEN_OVER))))
        assertEquals(io.spatia.app.ui.HomeStatus.REFUSED,
            io.spatia.app.ui.homeStatus(true, true, listOf(g(io.spatia.app.system.EffectState.REFUSED))))
        assertEquals(io.spatia.app.ui.HomeStatus.REFUSED_ANNOUNCED_ONLY,
            io.spatia.app.ui.homeStatus(true, true, listOf(g(io.spatia.app.system.EffectState.REFUSED), app)))
    }

    @Test fun phoneBrands() {
        assertEquals(io.spatia.app.ui.Brand.XIAOMI, io.spatia.app.ui.brandOf("Xiaomi"))
        assertEquals(io.spatia.app.ui.Brand.XIAOMI, io.spatia.app.ui.brandOf("POCO"))
        assertEquals(io.spatia.app.ui.Brand.OPPO_REALME, io.spatia.app.ui.brandOf("realme"))
        assertEquals(io.spatia.app.ui.Brand.ONEPLUS, io.spatia.app.ui.brandOf("OnePlus"))
        assertEquals(io.spatia.app.ui.Brand.VIVO, io.spatia.app.ui.brandOf("vivo"))
        assertEquals(io.spatia.app.ui.Brand.OTHER, io.spatia.app.ui.brandOf(""))
    }

    @Test fun balanceAndSpatialMapping() {
        val c = SessionEffectsManager.balanceDb(0f)
        assertEquals(0f, c[0]); assertEquals(0f, c[1])
        val r = SessionEffectsManager.balanceDb(0.5f) // towards right: left channel is cut
        assertTrue(r[0] < -5f && r[0] > -7f); assertEquals(0f, r[1])
        assertEquals(-60f, SessionEffectsManager.balanceDb(-1f)[1])
        assertEquals(600.toShort(), SessionEffectsManager.virtualizerStrength(1f))
        assertEquals(1000.toShort(), SessionEffectsManager.virtualizerStrength(2f))
        assertEquals(0.toShort(), SessionEffectsManager.virtualizerStrength(0f))
    }

    @Test fun bypassGivesFlatTargets() {
        val t = SessionEffectsManager.computeTargets(SoundSettings(), OutputSafety.forType(OutputType.WIRED), 0f, 0f, true)
        assertTrue(t.bandGainsDb.all { kotlin.math.abs(it) < 0.01f })
    }

    @Test fun testSoundIsShortStereoAndNotSilent() {
        val pcm = io.spatia.app.system.TestSound.pcm()
        assertEquals((io.spatia.app.system.TestSound.SECONDS * io.spatia.app.system.TestSound.RATE).toInt() * 2, pcm.size)
        assertTrue(pcm.any { kotlin.math.abs(it.toInt()) > 3000 })
        assertEquals(0, pcm[0].toInt()) // faded in, no click
    }
}
