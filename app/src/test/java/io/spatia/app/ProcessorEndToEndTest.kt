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
}
