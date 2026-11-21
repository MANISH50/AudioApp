package io.spatia.app.system

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.spatia.dsp.sample.SampleTrack

/**
 * A few seconds of music played the way any other app plays (a plain media AudioTrack with no effects of its own),
 * so what the user hears is exactly what Spatia's system-wide effects do to YouTube, Netflix and the rest.
 */
object TestSound {
    const val RATE = 48000
    const val SECONDS = 6.0
    /** Starts in the groove and runs into the voice section, so EQ, bass, voice and rooms are all audible. */
    private const val START_S = SampleTrack.VOICE_START_S - 3.0

    fun pcm(): ShortArray {
        val st = SampleTrack.render(RATE)
        val from = (START_S * RATE).toInt()
        val frames = (SECONDS * RATE).toInt().coerceAtMost(st[0].size - from)
        val fade = RATE / 10
        return ShortArray(frames * 2) { i ->
            val f = i / 2
            val env = minOf(1f, f / fade.toFloat(), (frames - f) / fade.toFloat())
            (st[i % 2][from + f] * env * 32767f).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun play(): AudioTrack {
        val data = pcm()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        track.write(data, 0, data.size)
        track.play()
        return track
    }
}
