package io.spatia.app.system

import android.media.audiofx.AudioEffect
import io.spatia.app.data.AppState

/** A validated "open your equaliser for my session" request from another app. */
data class EffectPanelRequest(val sessionId: Int, val packageName: String) {
    companion object {
        /** Untrusted extras from any app: returns null unless the action, session and package are all valid. */
        fun parse(action: String?, sessionId: Int, packageName: String?): EffectPanelRequest? {
            if (action != AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL) return null
            if (sessionId <= 0) return null
            val pkg = packageName?.take(255)?.takeIf { AppState.PACKAGE_RX.matches(it) } ?: "unknown"
            return EffectPanelRequest(sessionId, pkg)
        }
    }
}
