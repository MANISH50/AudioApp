package io.spatia.app.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Bit-perfect output to USB DACs via AudioMixerAttributes (Android 14+). When active, Spatia's DSP is bypassed
 * because any processing would defeat the purpose.
 */
object BitPerfect {
    val osSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private val mediaAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun isSupported(context: Context, device: AudioDeviceInfo?): Boolean =
        osSupported && device != null && options(context, device).isNotEmpty()

    @SuppressLint("NewApi")
    fun apply(context: Context, device: AudioDeviceInfo?, enable: Boolean): Boolean {
        if (!osSupported || device == null) return false
        return applyApi34(context, device, enable)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun applyApi34(context: Context, device: AudioDeviceInfo, enable: Boolean): Boolean {
        val am = context.getSystemService(AudioManager::class.java)
        return if (enable) {
            val best = options(context, device).maxByOrNull { it.format.sampleRate } ?: return false
            am.setPreferredMixerAttributes(mediaAttrs, device, best)
        } else {
            am.clearPreferredMixerAttributes(mediaAttrs, device)
        }
    }

    @SuppressLint("NewApi")
    private fun options(context: Context, device: AudioDeviceInfo): List<AudioMixerAttributes> {
        if (!osSupported) return emptyList()
        val am = context.getSystemService(AudioManager::class.java)
        return am.getSupportedMixerAttributes(device)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
    }
}
