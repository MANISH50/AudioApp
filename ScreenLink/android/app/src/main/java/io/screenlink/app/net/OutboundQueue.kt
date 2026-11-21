package io.screenlink.app.net

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What the writer thread sends next. Control messages (pairing, keys, pings) always go first and are never dropped
 * while there is room. Video is bounded: when the network can't keep up, [offerVideoFrame] refuses new frames and the
 * caster drops frames until the next keyframe instead of building up delay. A new [offerVideoConfig] discards any
 * queued frames from the previous configuration, so the laptop never decodes old frames with new parameters.
 */
class OutboundQueue(private val controlCapacity: Int = 256, private val videoCapacity: Int = 8) {
    class Item(val type: Int, val payload: ByteArray)

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val control = ArrayDeque<Item>()
    private val video = ArrayDeque<Item>()
    private var closed = false

    fun offerControl(type: Int, payload: ByteArray = FrameWriter.EMPTY): Boolean = lock.withLock {
        if (closed || control.size >= controlCapacity) return false
        control.addLast(Item(type, payload))
        ready.signal()
        true
    }

    fun offerVideoConfig(payload: ByteArray): Boolean = lock.withLock {
        if (closed) return false
        video.clear()
        video.addLast(Item(Protocol.VIDEO_CONFIG, payload))
        ready.signal()
        true
    }

    fun offerVideoFrame(payload: ByteArray): Boolean = lock.withLock {
        if (closed || video.size >= videoCapacity) return false
        video.addLast(Item(Protocol.VIDEO_FRAME, payload))
        ready.signal()
        true
    }

    /** Next item, or null after [timeoutMs] with nothing to send, or when closed. */
    fun take(timeoutMs: Long): Item? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && control.isEmpty() && video.isEmpty()) {
            if (nanos <= 0) return null
            nanos = ready.awaitNanos(nanos)
        }
        if (closed) return null
        control.pollFirst() ?: video.pollFirst()
    }

    fun close() = lock.withLock {
        closed = true
        control.clear()
        video.clear()
        ready.signalAll()
    }

    val isClosed: Boolean get() = lock.withLock { closed }
}
