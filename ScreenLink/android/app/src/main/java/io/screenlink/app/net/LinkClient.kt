package io.screenlink.app.net

import android.util.Log
import io.screenlink.app.data.PairedLaptop
import io.screenlink.app.data.PairingStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

enum class LinkFailure { UNREACHABLE, IDENTITY_CHANGED, REJECTED, PAIRING_TIMEOUT, LOST, INCOMPATIBLE, LAPTOP_CLOSED }

sealed interface LinkState {
    data object Idle : LinkState
    data class Connecting(val name: String) : LinkState
    /** First pairing: the user must compare [code] with the one on the laptop. */
    data class ConfirmCode(val name: String, val code: String) : LinkState
    data class WaitingForLaptop(val name: String, val code: String) : LinkState
    data class Connected(val name: String, val host: String) : LinkState
    data class Failed(val reason: LinkFailure) : LinkState
}

private sealed interface Outcome {
    /** Was connected (and is now disconnected); retry using this pin. */
    data class Paired(val fingerprintHex: String) : Outcome
    data class Retry(val reason: LinkFailure) : Outcome
    data class Fatal(val reason: LinkFailure) : Outcome
}

data class LinkTarget(val host: String, val port: Int, val name: String, val pinnedFingerprintHex: String?)

/**
 * The one connection to a laptop, shared by casting and the keyboard. Runs on its own threads: one reads (and does
 * the handshake), one writes from [OutboundQueue] and sends a PING when idle for 5 s. A paired laptop that drops off
 * (Wi-Fi blip, laptop asleep) is retried with backoff for about two minutes.
 */
class LinkClient(private val pairings: PairingStore, private val deviceName: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Called on the reader thread when the laptop asks for a keyframe (new viewer, decoder error). */
    @Volatile var onKeyframeRequest: (() -> Unit)? = null

    /** Called on the reader thread with CONTROL_* messages (laptop controlling the phone). See RemoteControl. */
    @Volatile var onControl: ((Int, ByteArray) -> Unit)? = null

    private val lock = Any()
    private var session: Session? = null

    val isConnected: Boolean get() = _state.value is LinkState.Connected

    fun connect(target: LinkTarget) {
        synchronized(lock) {
            session?.stop()
            session = Session(target).also { it.start() }
        }
    }

    fun disconnect() {
        synchronized(lock) {
            session?.stop()
            session = null
        }
        _state.value = LinkState.Idle
    }

    /** The user's answer to "Do the codes match?". */
    fun confirmCode(match: Boolean) {
        synchronized(lock) { session }?.decisions?.offer(match)
    }

    private fun current(): Session? = synchronized(lock) { session }?.takeIf { _state.value is LinkState.Connected }

    fun sendText(text: String): Boolean {
        if (text.isEmpty()) return true
        val s = current() ?: return false
        // Split long input (pastes) into protocol-sized pieces without breaking characters.
        var rest = text
        while (rest.isNotEmpty()) {
            val piece = Messages.truncateUtf8(rest, Protocol.MAX_TEXT_BYTES)
            if (!s.queue.offerControl(Protocol.KEY_TEXT, Messages.keyText(piece))) return false
            rest = rest.substring(piece.length)
        }
        return true
    }

    fun sendKey(vk: Int, modifiers: Int = 0): Boolean {
        if (vk !in Keys.ALLOWED) return false
        return current()?.queue?.offerControl(Protocol.KEY_PRESS, Messages.keyPress(vk, modifiers)) ?: false
    }

    fun sendCastState(casting: Boolean) {
        current()?.queue?.offerControl(Protocol.CAST_STATE, Messages.flag(casting))
    }

    fun sendControlState(ready: Boolean) {
        current()?.queue?.offerControl(Protocol.CONTROL_STATE, Messages.flag(ready))
    }

    fun offerVideoConfig(payload: ByteArray): Boolean = current()?.queue?.offerVideoConfig(payload) ?: false
    fun offerVideoFrame(payload: ByteArray): Boolean = current()?.queue?.offerVideoFrame(payload) ?: false

    private inner class Session(private val target: LinkTarget) {
        val decisions = LinkedBlockingQueue<Boolean>(1)
        @Volatile var queue = OutboundQueue()
        @Volatile private var stopped = false
        @Volatile private var socket: SecureSocket? = null
        private val thread = Thread({ run() }, "screenlink-link")

        fun start() = thread.start()

        fun stop() {
            stopped = true
            queue.offerControl(Protocol.BYE)
            // Give the writer a moment to send BYE, then close.
            Thread {
                Thread.sleep(150)
                queue.close()
                runCatching { socket?.socket?.close() }
            }.start()
            decisions.offer(false)
        }

        private fun publish(s: LinkState) {
            if (!stopped) _state.value = s
        }

        private fun run() {
            var pinned = target.pinnedFingerprintHex
            var attempt = 0
            val giveUpAt = { System.currentTimeMillis() + 120_000 }
            var deadline = giveUpAt()
            while (!stopped) {
                publish(LinkState.Connecting(target.name))
                val result = runOnce(pinned)
                if (stopped) return
                when (result) {
                    is Outcome.Paired -> { pinned = result.fingerprintHex; attempt = 0; deadline = giveUpAt() }
                    is Outcome.Fatal -> { publish(LinkState.Failed(result.reason)); return }
                    is Outcome.Retry -> {
                        // Only a laptop we're paired with is retried automatically.
                        if (pinned == null || System.currentTimeMillis() > deadline) {
                            publish(LinkState.Failed(result.reason)); return
                        }
                    }
                }
                val delay = minOf(8_000L, 1_000L shl minOf(attempt, 3))
                attempt++
                try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
                queue = OutboundQueue()
            }
        }

        private fun runOnce(pinnedHex: String?): Outcome {
            val pin = pinnedHex?.let { Pairing.fromHex(it) }
            val ss = try {
                SecureChannel.connect(target.host, target.port, pin)
            } catch (e: SSLHandshakeException) {
                Log.w(TAG, "handshake failed: ${e.message}")
                return if (pin != null && e.message?.contains("identity changed") == true) Outcome.Fatal(LinkFailure.IDENTITY_CHANGED)
                else Outcome.Retry(LinkFailure.UNREACHABLE)
            } catch (e: IOException) {
                Log.w(TAG, "connect failed: ${e.message}")
                return Outcome.Retry(LinkFailure.UNREACHABLE)
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "tls setup failed: ${e.message}")
                return Outcome.Fatal(LinkFailure.UNREACHABLE)
            }
            socket = ss
            if (stopped) { runCatching { ss.socket.close() }; return Outcome.Retry(LinkFailure.LOST) }
            val q = queue
            val writer = Thread({ writeLoop(ss, q) }, "screenlink-writer").apply { start() }
            var connected = false
            val fpHex = Pairing.toHex(ss.laptopFingerprint)
            try {
                q.offerControl(Protocol.HELLO, Messages.hello(deviceName()))
                val reader = FrameReader(ss.socket.inputStream)
                val first = reader.read()
                if (first.type != Protocol.HELLO) return Outcome.Fatal(LinkFailure.INCOMPATIBLE)
                val hello = Messages.parseHello(first.payload)
                if (hello.version != Protocol.VERSION) return Outcome.Fatal(LinkFailure.INCOMPATIBLE)
                val laptopName = hello.name.ifBlank { target.name }
                val code = Pairing.code(ss.laptopFingerprint, ss.phoneFingerprint)

                if (pin == null) {
                    publish(LinkState.ConfirmCode(laptopName, code))
                    decisions.clear()
                    val ok = decisions.poll(60, TimeUnit.SECONDS)
                    if (ok != true) {
                        q.offerControl(Protocol.PAIR_CONFIRM, Messages.flag(false))
                        Thread.sleep(150)
                        return Outcome.Fatal(if (ok == null) LinkFailure.PAIRING_TIMEOUT else LinkFailure.REJECTED)
                    }
                }
                q.offerControl(Protocol.PAIR_CONFIRM, Messages.flag(true))
                publish(LinkState.WaitingForLaptop(laptopName, code))

                while (!stopped) {
                    val f = reader.read()
                    when (f.type) {
                        Protocol.PAIR_RESULT -> {
                            if (!Messages.parseFlag(f.payload)) return Outcome.Fatal(LinkFailure.REJECTED)
                            if (!connected) {
                                connected = true
                                scope.launch { pairings.save(PairedLaptop(fpHex, target.host, target.port, laptopName)) }
                                publish(LinkState.Connected(laptopName, target.host))
                            }
                        }
                        Protocol.PING -> q.offerControl(Protocol.PONG, f.payload.copyOf(minOf(8, f.payload.size)))
                        Protocol.PONG -> Unit
                        Protocol.REQUEST_KEYFRAME -> if (connected) onKeyframeRequest?.invoke()
                        in Protocol.CONTROL_POINTER..Protocol.CONTROL_KEY -> if (connected) onControl?.invoke(f.type, f.payload)
                        Protocol.BYE -> return if (connected) Outcome.Fatal(LinkFailure.LAPTOP_CLOSED) else Outcome.Fatal(LinkFailure.REJECTED)
                        else -> Unit // unknown types are ignored, for forward compatibility
                    }
                }
                return Outcome.Retry(LinkFailure.LOST)
            } catch (e: InterruptedException) {
                return Outcome.Retry(LinkFailure.LOST)
            } catch (e: ProtocolException) {
                Log.w(TAG, "protocol error: ${e.message}")
                return Outcome.Fatal(LinkFailure.INCOMPATIBLE)
            } catch (e: SocketTimeoutException) {
                return if (connected) Outcome.Paired(fpHex) else Outcome.Retry(LinkFailure.LOST)
            } catch (e: EOFException) {
                return if (connected) Outcome.Paired(fpHex) else Outcome.Fatal(LinkFailure.REJECTED)
            } catch (e: SSLException) {
                return if (connected) Outcome.Paired(fpHex) else Outcome.Retry(LinkFailure.LOST)
            } catch (e: IOException) {
                return if (connected) Outcome.Paired(fpHex) else Outcome.Retry(LinkFailure.LOST)
            } finally {
                q.close()
                runCatching { ss.socket.close() }
                writer.join(500)
                socket = null
            }
        }

        private fun writeLoop(ss: SecureSocket, q: OutboundQueue) {
            try {
                val w = FrameWriter(ss.socket.outputStream.buffered(64 * 1024))
                while (!q.isClosed) {
                    val item = q.take(5_000)
                    if (item == null) {
                        if (q.isClosed) break
                        w.write(Protocol.PING, Messages.ping(System.nanoTime()))
                    } else {
                        w.write(item.type, item.payload)
                    }
                    w.flush()
                    if (item?.type == Protocol.BYE) break
                }
            } catch (e: IOException) {
                Log.w(TAG, "write failed: ${e.message}")
                runCatching { ss.socket.close() }
            }
        }
    }

    private companion object { const val TAG = "ScreenLink" }
}
