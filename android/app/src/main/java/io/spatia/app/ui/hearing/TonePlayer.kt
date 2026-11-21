package io.spatia.app.ui.hearing

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.spatia.dsp.Db
import kotlin.math.PI
import kotlin.math.sin

/** Plays a short, faded sine tone to one ear at a given dBFS level. Used only by the hearing check. */
object TonePlayer {
    private const val RATE = 48000

    fun play(freqHz: Float, levelDbfs: Float, leftEar: Boolean): AudioTrack {
        val frames = RATE * 1 // 1 s
        val fade = RATE / 20
        val amp = Db.toLinear(levelDbfs.coerceIn(-90f, -10f))
        val pcm = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val env = minOf(1f, i / fade.toFloat(), (frames - i) / fade.toFloat())
            val v = (sin(2 * PI * freqHz * i / RATE) * amp * env * 32767).toInt().toShort()
            pcm[2 * i] = if (leftEar) v else 0
            pcm[2 * i + 1] = if (leftEar) 0 else v
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        return track
    }
}
