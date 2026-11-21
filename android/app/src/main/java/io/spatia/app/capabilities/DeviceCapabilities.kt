package io.spatia.app.capabilities

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import io.spatia.app.R
import io.spatia.app.SpatiaApp
import io.spatia.app.spatial.SpatializerBridge

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

        val sp = SpatializerBridge.query(context)
        add(
            when {
                !sp.osSupported -> Capability(R.string.cap_spatializer, CapStatus.UNAVAILABLE, R.string.cap_spatializer_os)
                !sp.available || sp.level == 0 -> Capability(R.string.cap_spatializer, CapStatus.UNAVAILABLE, R.string.cap_spatializer_device)
                !sp.enabled -> Capability(R.string.cap_spatializer, CapStatus.LIMITED, R.string.cap_spatializer_off)
                else -> Capability(R.string.cap_spatializer, CapStatus.AVAILABLE, R.string.cap_spatializer_on)
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
        add(
            if (se.virtualizerSupported) Capability(R.string.cap_system_spatial, CapStatus.LIMITED, R.string.cap_system_spatial_ok)
            else Capability(R.string.cap_system_spatial, CapStatus.UNAVAILABLE, R.string.cap_system_spatial_reason),
        )
        val global = se.sessions.value.firstOrNull { it.isGlobal }
        add(
            when (global?.eq) {
                io.spatia.app.system.EffectState.ACTIVE -> Capability(R.string.cap_global, CapStatus.AVAILABLE, R.string.cap_global_ok)
                io.spatia.app.system.EffectState.TAKEN_OVER -> Capability(R.string.cap_global, CapStatus.LIMITED, R.string.cap_global_taken)
                null, io.spatia.app.system.EffectState.IDLE -> Capability(R.string.cap_global, CapStatus.LIMITED, R.string.cap_global_reason)
                else -> Capability(R.string.cap_global, CapStatus.UNAVAILABLE, R.string.cap_global_refused)
            },
        )

        val notif = api < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        add(Capability(R.string.cap_notifications, if (notif) CapStatus.AVAILABLE else CapStatus.LIMITED,
            if (notif) R.string.cap_notifications_ok else R.string.cap_notifications_denied))
    }
}
