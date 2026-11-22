package io.screenlink.app.control

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.net.LinkClient
import io.screenlink.app.net.LinkState
import io.screenlink.app.net.Messages
import io.screenlink.app.net.Protocol
import io.screenlink.app.net.ProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Decides whether the laptop may control the phone right now, and carries out its control messages. Allowed only when
 * all are true: connected to the paired laptop, casting the entire screen, "Let the laptop control this phone" on,
 * and the accessibility service switched on. The laptop is told the current state, so it can explain what's missing.
 */
class RemoteControl(scope: CoroutineScope, private val link: LinkClient, allowControl: Flow<Boolean>) {
    private val main = Handler(Looper.getMainLooper())
    private val gestures = GestureBuilder { SystemClock.uptimeMillis() }

    val ready: StateFlow<Boolean> = combine(
        link.state, ScreenCastService.casting, ScreenCastService.wholeScreen, allowControl, ControlService.enabled,
    ) { state, casting, whole, allowed, service ->
        state is LinkState.Connected && casting && whole && allowed && service
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    init {
        link.onControl = ::onControl
        scope.launch {
            // Also re-sent on every new connection, so the laptop always knows.
            combine(ready, link.state) { r, s -> r to (s is LinkState.Connected) }.collect { (r, connected) ->
                if (connected) link.sendControlState(r)
            }
        }
    }

    /** Called on the network thread with CONTROL_* messages from the connected laptop. */
    private fun onControl(type: Int, payload: ByteArray) {
        if (!ready.value) return
        try {
            when (type) {
                Protocol.CONTROL_POINTER -> {
                    val p = Messages.parsePointer(payload)
                    val stroke = synchronized(gestures) { gestures.onPointer(p.action, p.x, p.y) } ?: return
                    main.post { ControlService.current()?.perform(stroke) }
                }
                Protocol.CONTROL_SCROLL -> {
                    val s = Messages.parseScroll(payload)
                    val stroke = GestureBuilder.scroll(s.x, s.y, s.delta) ?: return
                    main.post { ControlService.current()?.perform(stroke) }
                }
                Protocol.CONTROL_GLOBAL -> {
                    val code = Messages.parseCode(payload, Messages.GLOBAL_NOTIFICATIONS)
                    main.post { ControlService.current()?.global(code) }
                }
                Protocol.CONTROL_TEXT -> {
                    val text = Messages.parseText(payload).filter { it == '\n' || !it.isISOControl() }
                    if (text.isNotEmpty()) main.post { ControlService.current()?.type(text) }
                }
                Protocol.CONTROL_KEY -> {
                    val code = Messages.parseCode(payload, Messages.KEY_ENTER)
                    main.post { ControlService.current()?.key(code) }
                }
            }
        } catch (e: ProtocolException) {
            Log.w("ScreenLinkControl", "ignored control message: ${e.message}")
        }
    }
}
