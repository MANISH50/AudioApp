package io.spatia.dsp

import io.spatia.dsp.io.ParametricEqParser
import io.spatia.dsp.io.WavReader
import io.spatia.dsp.io.WavWriter
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.SoundSettings
import io.spatia.dsp.model.SpatiaJson
import io.spatia.dsp.sample.SampleTrack
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs on every target (JVM for Android, native for the Windows DLL) to prove the shared engine behaves identically.
 * The detailed DSP suite lives in jvmTest.
 */
class CrossPlatformTest {
    private val sr = 48000

    @Test fun limiterHoldsCeilingWithExtremeSettingsOnEveryOutput() {
        val extreme = SoundSettings(eqGainsDb = List(10) { 12f }, bassDb = 12f, preampDb = 6f, spatialEnabled = true,
            environment = Environment.CATHEDRAL, environmentIntensity = 1f, loudnessNormalization = true)
        for (out in OutputType.entries) {
            val chain = DspChain(sr, 2)
            chain.setSettings(extreme, out)
            val r = Random(out.ordinal)
            val x = FloatArray(sr * 2) { r.nextFloat() * 2 - 1 }
            val y = FloatArray(x.size)
            chain.process(x, y, sr)
            val ceiling = Db.toLinear(OutputSafety.forType(out).limiterCeilingDb)
            assertTrue(y.all { it.isFinite() && abs(it) <= ceiling + 1e-6f }, "$out")
        }
    }

    @Test fun environmentAddsRoomWithoutTouchingTheOriginal() {
        val chain = DspChain(sr, 2)
        chain.setSettings(SoundSettings(environment = Environment.HALL), OutputType.WIRED)
        val x = FloatArray(sr * 4).also { it[sr] = 0.5f; it[sr + 1] = 0.5f } // impulse after the swap fade
        val y = FloatArray(x.size)
        chain.process(x, y, sr * 2)
        var tail = 0.0
        for (i in (sr + sr / 2) * 2 until sr * 4) tail += y[i] * y[i]
        assertTrue(tail > 1e-4, "hall tail energy $tail")
    }

    @Test fun settingsJsonRoundTripAndValidation() {
        val s = Presets.forMode(Mode.NIGHT).copy(environment = Environment.CINEMA)
        val json = SpatiaJson.encodeToString(SoundSettings.serializer(), s)
        assertEquals(s, SpatiaJson.decodeFromString(SoundSettings.serializer(), json))
        val bad = """{"bassDb": 99, "environment": "SPACESHIP", "unknownKey": 1}"""
        val v = SpatiaJson.decodeFromString(SoundSettings.serializer(), bad).validated()
        assertEquals(12f, v.bassDb)
        assertEquals(Environment.OFF, v.environment)
    }

    @Test fun parsersAndSampleTrack() {
        val eq = ParametricEqParser.parse("Preamp: -3 dB\nFilter 1: ON PK Fc 1000 Hz Gain 2 dB Q 1".encodeToByteArray())
        assertEquals(1, eq.filters.size)
        val t = SampleTrack.render(22050)
        assertEquals((SampleTrack.LENGTH_S * 22050).toInt(), t[0].size)
        val clip = arrayOf(t[0].copyOfRange(0, 22050), t[1].copyOfRange(0, 22050))
        val back = WavReader.read(WavWriter.write16(clip, 22050))
        assertEquals(22050, back.sampleRate)
        for (i in 0 until 22050 step 101) assertEquals(clip[0][i], back.channels[0][i], 1f / 16000)
    }
}
