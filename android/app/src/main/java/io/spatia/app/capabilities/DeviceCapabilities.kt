package io.spatia.app.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.spatia.app.R
import io.spatia.app.SpatiaApp
import io.spatia.app.player.BitPerfect
import io.spatia.app.player.FormatSupport
import io.spatia.app.spatial.SpatializerBridge
import io.spatia.dsp.model.OutputType

enum class CapStatus { AVAILABLE, LIMITED, UNAVAILABLE }

/** One row of the Device Status screen: what it is, whether it works here, and why not if it doesn't. */
data class Capability(
    @StringRes val title: Int,
    val status: CapStatus,
    @StringRes val reason: Int,
    val reasonArgs: List<Any> = emptyList(),
)

/** Queries documented platform APIs only. Re-run when outputs change. */
class DeviceCapabilities(private val context: Context) {
    private val app get() = context.applicationContext as SpatiaApp

    @android.annotation.SuppressLint("InlinedApi") // POST_NOTIFICATIONS is only checked on API 33+
    fun query(): List<Capability> = buildList {
        val api = Build.VERSION.SDK_INT
        add(Capability(R.string.cap_android, CapStatus.AVAILABLE, R.string.cap_android_reason, listOf(Build.VERSION.RELEASE, api)))
        add(Capability(R.string.cap_player_dsp, CapStatus.AVAILABLE, R.string.cap_player_dsp_reason))

        val sp = SpatializerBridge.query(context)
        add(
            when {
                !sp.osSupported -> Capability(R.string.cap_spatializer, CapStatus.UNAVAILABLE, R.string.cap_spatializer_os)
                !sp.available || sp.level == 0 -> Capability(R.string.cap_spatializer, CapStatus.UNAVAILABLE, R.string.cap_spatializer_device)
                !sp.enabled -> Capability(R.string.cap_spatializer, CapStatus.LIMITED, R.string.cap_spatializer_off)
                else -> Capability(R.string.cap_spatializer, CapStatus.AVAILABLE, R.string.cap_spatializer_on)
            },
        )
        add(Capability(R.string.cap_spatial_app, CapStatus.AVAILABLE, R.string.cap_spatial_app_reason))
        add(Capability(R.string.cap_environment, CapStatus.AVAILABLE, R.string.cap_environment_reason))

        val ht = app.headTracker
        add(
            when {
                !ht.isSupportedByOs -> Capability(R.string.cap_head_tracking, CapStatus.UNAVAILABLE, R.string.cap_head_tracking_os)
                ht.available.value || sp.headTrackerAvailable -> Capability(R.string.cap_head_tracking, CapStatus.AVAILABLE, R.string.cap_head_tracking_ok)
                else -> Capability(R.string.cap_head_tracking, CapStatus.UNAVAILABLE, R.string.cap_head_tracking_no_sensor)
            },
        )

        val se = app.sessionEffects
        add(
            when {
                se.dynamicsProcessingSupported -> Capability(R.string.cap_system_wide, CapStatus.LIMITED, R.string.cap_system_wide_dp)
                se.equalizerSupported -> Capability(R.string.cap_system_wide, CapStatus.LIMITED, R.string.cap_system_wide_eq)
                else -> Capability(R.string.cap_system_wide, CapStatus.UNAVAILABLE, R.string.cap_system_wide_none)
            },
        )
        add(
            if (se.environmentalReverbSupported) Capability(R.string.cap_environment_other, CapStatus.LIMITED, R.string.cap_environment_other_ok)
            else Capability(R.string.cap_environment_other, CapStatus.UNAVAILABLE, R.string.cap_environment_other_no),
        )
        add(Capability(R.string.cap_global, CapStatus.LIMITED, R.string.cap_global_reason))
        add(Capability(R.string.cap_system_spatial, CapStatus.UNAVAILABLE, R.string.cap_system_spatial_reason))

        val usb = app.outputs.state.value.connected.firstOrNull { it.type == OutputType.USB }
        add(
            when {
                !BitPerfect.osSupported -> Capability(R.string.cap_bit_perfect, CapStatus.UNAVAILABLE, R.string.cap_bit_perfect_os)
                usb == null -> Capability(R.string.cap_bit_perfect, CapStatus.UNAVAILABLE, R.string.cap_bit_perfect_no_usb)
                BitPerfect.isSupported(context, app.outputs.deviceInfo(usb.id)) ->
                    Capability(R.string.cap_bit_perfect, CapStatus.AVAILABLE, R.string.cap_bit_perfect_ok)
                else -> Capability(R.string.cap_bit_perfect, CapStatus.UNAVAILABLE, R.string.cap_bit_perfect_dac)
            },
        )

        for ((label, mime) in listOf("FLAC" to "audio/flac", "Opus" to "audio/opus", "AAC" to "audio/mp4a-latm", "MP3" to "audio/mpeg")) {
            val ok = FormatSupport.hasDecoder(mime)
            add(Capability(R.string.cap_codec, if (ok) CapStatus.AVAILABLE else CapStatus.UNAVAILABLE,
                if (ok) R.string.cap_codec_ok else R.string.cap_codec_missing, listOf(label)))
        }
        add(Capability(R.string.cap_codec, CapStatus.AVAILABLE, R.string.cap_codec_wav, listOf("WAV")))
        add(
            if (FormatSupport.platformIamfDecoder()) Capability(R.string.cap_iamf, CapStatus.AVAILABLE, R.string.cap_iamf_ok)
            else Capability(R.string.cap_iamf, CapStatus.UNAVAILABLE, R.string.cap_iamf_missing),
        )

        val notif = api < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        add(Capability(R.string.cap_notifications, if (notif) CapStatus.AVAILABLE else CapStatus.LIMITED,
            if (notif) R.string.cap_notifications_ok else R.string.cap_notifications_denied))
    }
}
