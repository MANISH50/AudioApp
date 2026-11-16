package io.spatia.app.output

import android.media.AudioDeviceInfo
import io.spatia.dsp.model.OutputType

/** Maps AudioDeviceInfo.TYPE_* to our output classes. Pure, so it is unit-testable without a device. */
object DeviceMapping {
    fun map(type: Int): OutputType? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputType.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputType.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID,
        TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST -> OutputType.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputType.USB
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_IP, AudioDeviceInfo.TYPE_LINE_DIGITAL -> OutputType.HDMI_CAST
        else -> null
    }

    // Constants added after API 29; values are stable platform constants.
    const val TYPE_BLE_HEADSET = 26
    const val TYPE_BLE_SPEAKER = 27
    const val TYPE_BLE_BROADCAST = 30

    /** Stable, non-identifying key for per-device memory: a hash of type + product name, never the MAC address. */
    fun deviceKey(type: Int, productName: CharSequence?): String {
        val raw = "$type|${productName?.toString()?.take(64).orEmpty()}"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
