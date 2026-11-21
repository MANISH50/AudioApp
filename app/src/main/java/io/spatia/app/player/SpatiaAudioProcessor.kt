package io.spatia.app.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.spatia.dsp.DspChain
import io.spatia.dsp.analysis.Meters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Media3 audio processor that runs the full [DspChain] on decoded PCM. Accepts 16-bit or float input with 1, 2, 6 or
 * 8 channels and always outputs 16-bit stereo (the limiter keeps peaks below -1 dBFS, so no clipping on conversion).
 * Unsupported layouts and bit-perfect mode pass through untouched.
 */
@UnstableApi
class SpatiaAudioProcessor(private val engine: ChainHost) : BaseAudioProcessor() {
    private var chain: DspChain? = null
    private var meters: Meters? = null
    private var inFloat = FloatArray(0)
    private var outFloat = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount !in DspChain.SUPPORTED_CHANNELS) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate !in 8000..384000) return AudioProcessor.AudioFormat.NOT_SET
        if (engine.bitPerfect) return AudioProcessor.AudioFormat.NOT_SET
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        val fmt = inputAudioFormat
        val existing = chain
        if (existing == null || existing.sampleRate != fmt.sampleRate || existing.inChannels != fmt.channelCount) {
            existing?.let(engine::unregister)
            if (fmt == AudioProcessor.AudioFormat.NOT_SET) { chain = null; return }
            chain = DspChain(fmt.sampleRate, fmt.channelCount).also(engine::register)
            meters = Meters(fmt.sampleRate).also { engine.meters = it }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val c = chain ?: return
        val inCh = inputAudioFormat.channelCount
        val bytesPerSample = if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frames = inputBuffer.remaining() / (bytesPerSample * inCh)
        if (frames == 0) return
        if (inFloat.size < frames * inCh) inFloat = FloatArray(frames * inCh)
        if (outFloat.size < frames * 2) outFloat = FloatArray(frames * 2)

        val src = inputBuffer.order(ByteOrder.nativeOrder())
        if (bytesPerSample == 4) {
            for (i in 0 until frames * inCh) inFloat[i] = src.getFloat().let { if (it.isFinite()) it else 0f }
        } else {
            for (i in 0 until frames * inCh) inFloat[i] = src.getShort() / 32768f
        }
        c.process(inFloat, outFloat, frames)
        meters?.push(outFloat, frames, 2)

        val out = replaceOutputBuffer(frames * 2 * 2)
        for (i in 0 until frames * 2) {
            out.putShort((outFloat[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        out.flip()
    }

    override fun onReset() {
        chain?.let(engine::unregister)
        chain = null
        meters = null
    }
}
