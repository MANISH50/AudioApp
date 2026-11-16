package io.spatia.app.spatial

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build

/** Read-only view of the platform Spatializer (Android 12L+). We never try to force it on. */
data class SpatializerInfo(
    val osSupported: Boolean,
    val level: Int = 0,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val canSpatialize51: Boolean = false,
    val headTrackerAvailable: Boolean = false,
)

object SpatializerBridge {
    fun query(context: Context): SpatializerInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return SpatializerInfo(osSupported = false)
        val sp: Spatializer = context.getSystemService(AudioManager::class.java).spatializer
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).setSampleRate(48000).build()
        val head = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) sp.isHeadTrackerAvailable else false
        return SpatializerInfo(
            osSupported = true,
            level = sp.immersiveAudioLevel,
            available = sp.isAvailable,
            enabled = sp.isEnabled,
            canSpatialize51 = sp.canBeSpatialized(attrs, fmt),
            headTrackerAvailable = head,
        )
    }
}
