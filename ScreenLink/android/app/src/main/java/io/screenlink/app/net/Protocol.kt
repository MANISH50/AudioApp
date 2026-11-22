package io.screenlink.app.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * ScreenLink wire protocol v1 (mirrored in ScreenLink/windows/src/ScreenLink.Core/Protocol). See ScreenLink/PROTOCOL.md.
 *
 * Frame: `type:u8 | length:u32 big-endian | payload`. Payloads larger than [MAX_PAYLOAD] are rejected: the reader throws
 * and the connection is closed, so a peer can never make the other side allocate unbounded memory.
 */
object Protocol {
    const val VERSION = 1
    const val TCP_PORT = 47420
    const val DISCOVERY_PORT = 47421
    const val MAX_PAYLOAD = 4 * 1024 * 1024
    const val MAX_TEXT_BYTES = 1024
    const val MAX_NAME_BYTES = 128

    const val HELLO: Int = 0x01
    const val PAIR_CONFIRM: Int = 0x02
    const val PAIR_RESULT: Int = 0x03
    const val VIDEO_CONFIG: Int = 0x10
    const val VIDEO_FRAME: Int = 0x11
    const val REQUEST_KEYFRAME: Int = 0x12
    const val CAST_STATE: Int = 0x13
    const val KEY_TEXT: Int = 0x20
    const val KEY_PRESS: Int = 0x21
    const val CONTROL_POINTER: Int = 0x40
    const val CONTROL_SCROLL: Int = 0x41
    const val CONTROL_GLOBAL: Int = 0x42
    const val CONTROL_TEXT: Int = 0x43
    const val CONTROL_KEY: Int = 0x44
    const val CONTROL_STATE: Int = 0x45
    const val PING: Int = 0x30
    const val PONG: Int = 0x31
    const val BYE: Int = 0x3F

    const val MOD_CTRL = 1
    const val MOD_ALT = 2
    const val MOD_SHIFT = 4
    const val MOD_WIN = 8
}

class ProtocolException(message: String) : IOException(message)

/** One decoded frame. */
class Frame(val type: Int, val payload: ByteArray)

/** Reads frames from a stream; blocking. */
class FrameReader(input: InputStream) {
    private val din = DataInputStream(input)

    /** Returns the next frame, or throws [EOFException] when the peer closed the connection. */
    fun read(): Frame {
        val type = din.read()
        if (type < 0) throw EOFException()
        val length = din.readInt()
        if (length < 0 || length > Protocol.MAX_PAYLOAD) throw ProtocolException("frame too large: $length")
        val payload = ByteArray(length)
        din.readFully(payload)
        return Frame(type, payload)
    }
}

/** Writes frames; callers must serialise access (one writer thread). */
class FrameWriter(output: OutputStream) {
    private val dout = DataOutputStream(output)

    fun write(type: Int, payload: ByteArray = EMPTY) {
        require(type in 0..255)
        require(payload.size <= Protocol.MAX_PAYLOAD) { "payload too large" }
        dout.writeByte(type)
        dout.writeInt(payload.size)
        dout.write(payload)
    }

    fun flush() = dout.flush()

    companion object { val EMPTY = ByteArray(0) }
}

/** Builders and parsers for each message payload. All numbers big-endian. */
object Messages {
    fun hello(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeShort(Protocol.VERSION)
            writeString(this, truncateUtf8(name, Protocol.MAX_NAME_BYTES))
        }
        return out.toByteArray()
    }

    class Hello(val version: Int, val name: String)

    fun parseHello(p: ByteArray): Hello {
        val b = ByteBuffer.wrap(p)
        if (b.remaining() < 4) throw ProtocolException("short HELLO")
        val version = b.short.toInt() and 0xFFFF
        val name = readString(b, Protocol.MAX_NAME_BYTES)
        return Hello(version, name)
    }

    fun flag(value: Boolean) = byteArrayOf(if (value) 1 else 0)
    fun parseFlag(p: ByteArray): Boolean {
        if (p.size != 1) throw ProtocolException("bad flag")
        return p[0].toInt() != 0
    }

    fun videoConfig(width: Int, height: Int, csd: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + csd.size).putShort(width.toShort()).putShort(height.toShort()).put(csd).array()

    const val FRAME_FLAG_KEY = 1

    fun videoFrame(ptsUs: Long, keyFrame: Boolean, data: ByteArray, offset: Int = 0, length: Int = data.size): ByteArray =
        ByteBuffer.allocate(9 + length).putLong(ptsUs).put(if (keyFrame) FRAME_FLAG_KEY.toByte() else 0).put(data, offset, length).array()

    fun keyText(text: String): ByteArray {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= Protocol.MAX_TEXT_BYTES) { "text too long" }
        return bytes
    }

    /** A key tap: [key] is a Windows virtual-key code (see [Keys]), [modifiers] a MOD_* bitmask held during the tap. */
    fun keyPress(key: Int, modifiers: Int): ByteArray =
        ByteBuffer.allocate(3).putShort(key.toShort()).put(modifiers.toByte()).array()

    /** Laptop → phone: a pointer event; x/y are 0..65535 across the video picture. */
    class Pointer(val action: Int, val x: Int, val y: Int)
    const val POINTER_DOWN = 0
    const val POINTER_MOVE = 1
    const val POINTER_UP = 2
    const val POINTER_CANCEL = 3

    fun pointer(action: Int, x: Int, y: Int): ByteArray =
        ByteBuffer.allocate(5).put(action.toByte()).putShort(x.toShort()).putShort(y.toShort()).array()

    fun parsePointer(p: ByteArray): Pointer {
        if (p.size != 5) throw ProtocolException("bad CONTROL_POINTER")
        val b = ByteBuffer.wrap(p)
        val action = b.get().toInt() and 0xFF
        if (action > POINTER_CANCEL) throw ProtocolException("bad pointer action")
        return Pointer(action, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF)
    }

    class Scroll(val x: Int, val y: Int, val delta: Int)

    fun scroll(x: Int, y: Int, delta: Int): ByteArray =
        ByteBuffer.allocate(6).putShort(x.toShort()).putShort(y.toShort()).putShort(delta.toShort()).array()

    fun parseScroll(p: ByteArray): Scroll {
        if (p.size != 6) throw ProtocolException("bad CONTROL_SCROLL")
        val b = ByteBuffer.wrap(p)
        return Scroll(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.short.toInt())
    }

    const val GLOBAL_BACK = 1
    const val GLOBAL_HOME = 2
    const val GLOBAL_RECENTS = 3
    const val GLOBAL_NOTIFICATIONS = 4
    const val KEY_BACKSPACE = 1
    const val KEY_ENTER = 2

    /** A single-byte code (CONTROL_GLOBAL, CONTROL_KEY). */
    fun parseCode(p: ByteArray, max: Int): Int {
        if (p.size != 1) throw ProtocolException("bad code")
        val v = p[0].toInt() and 0xFF
        if (v !in 1..max) throw ProtocolException("unknown code")
        return v
    }

    fun parseText(p: ByteArray): String {
        if (p.size > Protocol.MAX_TEXT_BYTES) throw ProtocolException("text too long")
        val dec = StandardCharsets.UTF_8.newDecoder()
        return try {
            dec.decode(ByteBuffer.wrap(p)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw ProtocolException("invalid UTF-8")
        }
    }

    fun ping(nanos: Long): ByteArray = ByteBuffer.allocate(8).putLong(nanos).array()

    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        out.writeShort(bytes.size)
        out.write(bytes)
    }

    private fun readString(b: ByteBuffer, max: Int): String {
        if (b.remaining() < 2) throw ProtocolException("short string")
        val len = b.short.toInt() and 0xFFFF
        if (len > max || len > b.remaining()) throw ProtocolException("bad string length")
        val bytes = ByteArray(len)
        b.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    /** Cuts [s] to at most [maxBytes] UTF-8 bytes without splitting a character. */
    fun truncateUtf8(s: String, maxBytes: Int): String {
        if (s.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return s
        val sb = StringBuilder()
        var used = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = String(Character.toChars(cp))
            val n = chars.toByteArray(StandardCharsets.UTF_8).size
            if (used + n > maxBytes) break
            sb.append(chars)
            used += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}

/** Windows virtual-key codes used by the keyboard screen. The laptop accepts only codes in [ALLOWED]. */
object Keys {
    const val BACKSPACE = 0x08
    const val TAB = 0x09
    const val ENTER = 0x0D
    const val ESCAPE = 0x1B
    const val SPACE = 0x20
    const val PAGE_UP = 0x21
    const val PAGE_DOWN = 0x22
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28
    const val INSERT = 0x2D
    const val DELETE = 0x2E
    const val F1 = 0x70

    fun letter(c: Char): Int = c.uppercaseChar().code.also { require(it in 0x41..0x5A) }

    val ALLOWED: Set<Int> = buildSet {
        addAll(listOf(BACKSPACE, TAB, ENTER, ESCAPE, SPACE, PAGE_UP, PAGE_DOWN, END, HOME, LEFT, UP, RIGHT, DOWN, INSERT, DELETE))
        addAll(0x30..0x39) // 0-9
        addAll(0x41..0x5A) // A-Z
        addAll(F1 until F1 + 12) // F1-F12
    }
}
