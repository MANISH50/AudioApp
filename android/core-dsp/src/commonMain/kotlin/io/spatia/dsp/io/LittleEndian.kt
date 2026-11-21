package io.spatia.dsp.io

/** Little-endian byte helpers (multiplatform replacement for java.nio.ByteBuffer). */
internal object LittleEndian {
    fun i16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)).toShort().toInt()
    fun u16(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
    fun i32(b: ByteArray, p: Int): Int = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
        ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
    fun f32(b: ByteArray, p: Int): Float = Float.fromBits(i32(b, p))
    fun ascii(b: ByteArray, p: Int, n: Int): String = CharArray(n) { (b[p + it].toInt() and 0xFF).toChar() }.concatToString()

    class Writer(size: Int) {
        val bytes = ByteArray(size)
        var pos = 0
        fun ascii(s: String) = apply { s.forEach { bytes[pos++] = it.code.toByte() } }
        fun i16(v: Int) = apply { bytes[pos++] = v.toByte(); bytes[pos++] = (v shr 8).toByte() }
        fun i32(v: Int) = apply { repeat(4) { k -> bytes[pos++] = (v shr (8 * k)).toByte() } }
    }
}
