package io.spatia.app

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import io.spatia.app.player.ChainHost
import io.spatia.app.player.SpatiaAudioProcessor
import io.spatia.dsp.DspChain
import io.spatia.dsp.analysis.Meters
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.SoundSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real Media3 AudioProcessor contract (configure -> flush -> queueInput -> getOutput), exactly as
 * ExoPlayer's DefaultAudioSink does, to prove the built-in player's audio really goes through the DSP.
 */
@UnstableApi
class ProcessorEndToEndTest {
    private class Host(val settings: SoundSettings) : ChainHost {
        override val bitPerfect = false
        override var meters: Meters? = null
        var registered: DspChain? = null
        override fun register(chain: DspChain) { registered = chain; chain.setSettings(settings, OutputType.WIRED) }
        override fun unregister(chain: DspChain) { registered = null }
    }

    private val sr = 44100
    private val frames = sr * 2

    private fun pcm16Stereo(): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            // Short bursts so a room tail is clearly separable from the music.
            val env = if ((i / (sr / 4)) % 2 == 0) 1.0 else 0.0
            val v = (sin(2 * PI * 440 * i / sr) * 8000 * env).toInt().toShort()
            bb.putShort(v); bb.putShort(v)
        }
        bb.flip()
        return bb
    }

    private fun runThrough(settings: SoundSettings): ShortArray {
        val host = Host(settings)
        val p = SpatiaAudioProcessor(host)
        val out = p.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        assertTrue(p.isActive)
        assertEquals(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT), out)
        p.flush()
        assertTrue("processor must register its DSP chain", host.registered != null)
        val input = pcm16Stereo()
        val result = ShortArray(frames * 2)
        var n = 0
        while (input.hasRemaining()) {
            val chunk = input.slice().order(ByteOrder.nativeOrder())
            chunk.limit(minOf(4096, input.remaining()))
            p.queueInput(chunk)
            input.position(input.position() + (chunk.limit() - chunk.remaining()))
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) result[n++] = o.short
        }
        assertEquals(frames * 2, n)
        p.reset()
        assertTrue(host.registered == null)
        return result
    }

    /**
     * Energy in the gaps between bursts, where a room's tail lives. Skips the first 20 ms of each gap, which still
     * holds the end of the burst because of the limiter's look-ahead latency.
     */
    private fun gapEnergy(x: ShortArray): Double {
        var e = 0.0
        val q = sr / 4
        for (f in 0 until frames) if ((f / q) % 2 == 1 && f % q > sr / 50) e += x[2 * f].toDouble() * x[2 * f]
        return e
    }

    @Test fun neutralSettingsLeaveAudioEssentiallyUnchanged() {
        val y = runThrough(SoundSettings())
        assertTrue(gapEnergy(y) < 1e6)
    }

    @Test fun hallEnvironmentIsAudibleInPlayerOutput() {
        val dry = runThrough(SoundSettings())
        val hall = runThrough(SoundSettings(environment = Environment.HALL))
        assertTrue("hall gap energy ${gapEnergy(hall)} vs ${gapEnergy(dry)}", gapEnergy(hall) > gapEnergy(dry) * 100)
    }

    @Test fun modesChangeTheOutput() {
        val music = runThrough(Presets.forMode(Mode.MUSIC))
        val voice = runThrough(Presets.forMode(Mode.VOICE))
        assertFalse(music.contentEquals(voice))
    }

    // ---- The built-in sample track through the real processor ----

    private val sample = io.spatia.dsp.sample.SampleTrack.render(sr)

    private fun runSample(settings: SoundSettings): ShortArray {
        val host = Host(settings)
        val p = SpatiaAudioProcessor(host)
        p.configure(AudioProcessor.AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val n = sample[0].size
        val out = ShortArray(n * 2)
        var w = 0
        var f = 0
        while (f < n) {
            val frames = minOf(2048, n - f)
            val bb = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
            for (i in f until f + frames) {
                bb.putShort((sample[0][i] * 32767).toInt().toShort()); bb.putShort((sample[1][i] * 32767).toInt().toShort())
            }
            bb.flip()
            p.queueInput(bb)
            val o = p.output.order(ByteOrder.nativeOrder())
            while (o.hasRemaining()) out[w++] = o.short
            f += frames
        }
        return out
    }

    /** Energy in the silent gaps of the sample's claps section (skipping 20 ms of limiter latency). */
    private fun sampleGapEnergy(x: ShortArray): Double {
        val hits = io.spatia.dsp.sample.SampleTrack.HIT_TIMES_S
        val len = io.spatia.dsp.sample.SampleTrack.HIT_LENGTH_S
        var e = 0.0
        for (k in 0 until hits.size - 1) {
            val a = ((hits[k] + len + 0.02) * sr).toInt(); val b = (hits[k + 1] * sr).toInt()
            for (i in a until b) e += x[2 * i].toDouble() * x[2 * i] + x[2 * i + 1].toDouble() * x[2 * i + 1]
        }
        return e
    }

    @Test fun sampleTrackGapsAreSilentWithoutEnvironment() {
        assertEquals(0.0, sampleGapEnergy(runSample(SoundSettings())), 0.0)
    }

    @Test fun everyEnvironmentFillsTheSampleGaps() {
        for (env in Environment.entries.filter { it != Environment.OFF }) {
            val e = sampleGapEnergy(runSample(SoundSettings(environment = env)))
            assertTrue("$env should be audible in the gaps (energy $e)", e > 1e6)
        }
    }

    @Test fun intensityMeterChangesTheSampleTail() {
        val subtle = sampleGapEnergy(runSample(SoundSettings(environment = Environment.HALL, environmentIntensity = 0.25f)))
        val default = sampleGapEnergy(runSample(SoundSettings(environment = Environment.HALL)))
        val intense = sampleGapEnergy(runSample(SoundSettings(environment = Environment.HALL, environmentIntensity = 1f)))
        assertTrue("$subtle < $default < $intense", subtle < default && default < intense)
    }

    @Test fun everyModeSoundsDifferentOnTheSample() {
        val outputs = Mode.entries.filter { it != Mode.CUSTOM }.map { runSample(Presets.forMode(it)) }
        for (i in outputs.indices) for (j in i + 1 until outputs.size) assertFalse(outputs[i].contentEquals(outputs[j]))
    }
}
