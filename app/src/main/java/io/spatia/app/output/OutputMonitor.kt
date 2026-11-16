package io.spatia.app.output

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.ProfileSelector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OutputDevice(val type: OutputType, val platformType: Int, val name: String, val key: String, val id: Int)

data class OutputState(
    val connected: List<OutputDevice> = emptyList(),
    val detected: OutputType = OutputType.SPEAKER,
    val detectedDevice: OutputDevice? = null,
)

/** Tracks connected outputs through the documented AudioDeviceCallback. */
class OutputMonitor(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(OutputState())
    val state: StateFlow<OutputState> = _state

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
            val t = DeviceMapping.map(d.type) ?: return@mapNotNull null
            OutputDevice(t, d.type, d.productName?.toString().orEmpty(), DeviceMapping.deviceKey(d.type, d.productName), d.id)
        }
        val detected = ProfileSelector.activeOutput(devices.map { it.type }, null)
        _state.value = OutputState(devices, detected, devices.firstOrNull { it.type == detected })
    }

    fun deviceInfo(id: Int): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
}
