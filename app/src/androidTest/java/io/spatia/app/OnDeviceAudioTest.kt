package io.spatia.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.spatia.app.output.DeviceMapping
import io.spatia.app.system.SessionEffectsManager
import io.spatia.dsp.DspChain
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.Mode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device or emulator: `./gradlew connectedDebugAndroidTest`.
 * Checks the documented platform APIs Spatia depends on behave as expected on this device.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceAudioTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun outputsAreDetectedAndMapped() {
        val am = ctx.getSystemService(AudioManager::class.java)
        val mapped = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { DeviceMapping.map(it.type) }
        assertTrue("Expected at least one mappable output", mapped.isNotEmpty())
    }

    @Test fun dynamicsProcessingAttachesToOwnSessionAndDspRunsInRealTime() {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(48000).build()
        try {
            val app = ctx.applicationContext as SpatiaApp
            if (app.sessionEffects.dynamicsProcessingSupported) {
                val cfg = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, 10, false, 0, false, 0, true).build()
                val dp = DynamicsProcessing(0, track.audioSessionId, cfg)
                val t = SessionEffectsManager.computeTargets(Presets.forMode(Mode.MUSIC), OutputSafety.forType(OutputType.WIRED), 0f, 3f, true)
                dp.setInputGainAllChannelsTo(t.inputGainDb)
                dp.enabled = true
                assertTrue(dp.enabled)
                dp.release()
            }
            // The full chain must process one second of stereo audio well under one second on device.
            val chain = DspChain(48000, 2)
            chain.setSettings(Presets.forMode(Mode.MOVIE), OutputType.WIRED)
            val x = FloatArray(48000 * 2) { (Math.random().toFloat() - 0.5f) * 0.5f }
            val y = FloatArray(x.size)
            val start = System.nanoTime()
            chain.process(x, y, 48000)
            val ms = (System.nanoTime() - start) / 1e6
            assertTrue("DSP too slow: $ms ms for 1 s of audio", ms < 500)
        } finally {
            track.release()
        }
    }
}
