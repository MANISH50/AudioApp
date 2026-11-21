package io.spatia.dsp

import io.spatia.dsp.filters.FilterType
import io.spatia.dsp.io.ParametricEqParser
import io.spatia.dsp.io.WavReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    private val autoEq = """
        Preamp: -6.4 dB
        Filter 1: ON LSC Fc 105 Hz Gain 5.6 dB Q 0.70
        Filter 2: ON PK Fc 2400 Hz Gain -3.1 dB Q 1.85
        Filter 3: OFF PK Fc 5000 Hz Gain 2.0 dB Q 2.00
        Filter 4: ON HSC Fc 10000 Hz Gain -2.5 dB Q 0.70
    """.trimIndent()

    @Test fun parsesAutoEqFormat() {
        val r = ParametricEqParser.parse(autoEq)
        assertEquals(-6.4f, r.preampDb, 1e-4f)
        assertEquals(3, r.filters.size)
        assertEquals(FilterType.LOW_SHELF, r.filters[0].type)
        assertEquals(2400f, r.filters[1].freqHz)
        assertEquals(FilterType.HIGH_SHELF, r.filters[2].type)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun rejectsGarbageOversizeAndBinary() {
        assertThrows(ParametricEqParser.ParseException::class.java) { ParametricEqParser.parse("hello\nworld") }
        assertThrows(ParametricEqParser.ParseException::class.java) { ParametricEqParser.parse(ByteArray(ParametricEqParser.MAX_BYTES + 1)) }
        assertThrows(ParametricEqParser.ParseException::class.java) { ParametricEqParser.parse(byteArrayOf(0, 1, 2)) }
    }

    @Test fun outOfRangeValuesAreSkippedOrClamped() {
        val r = ParametricEqParser.parse(
            "Filter 1: ON PK Fc 99999 Hz Gain 3 dB Q 1\nFilter 2: ON PK Fc 1000 Hz Gain 99 dB Q 1\n; rm -rf /",
        )
        assertEquals(1, r.filters.size)
        assertEquals(20f, r.filters[0].gainDb)
        assertEquals(3, r.warnings.size)
    }

    @Test fun capsFilterCount() {
        val text = (1..40).joinToString("\n") { "Filter $it: ON PK Fc ${100 * it} Hz Gain 1 dB Q 1" }
        assertEquals(20, ParametricEqParser.parse(text).filters.size)
    }

    private fun wav16(frames: Int, channels: Int, rate: Int): ByteArray {
        val data = frames * channels * 2
        val bb = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        bb.put("data".toByteArray()).putInt(data)
        for (i in 0 until frames * channels) bb.putShort(if (i == 0) 16384 else 0)
        return bb.array()
    }

    @Test fun readsPcm16Wav() {
        val w = WavReader.read(wav16(480, 2, 48000))
        assertEquals(48000, w.sampleRate)
        assertEquals(2, w.channels.size)
        assertEquals(0.5f, w.channels[0][0], 1e-4f)
    }

    @Test fun rejectsBadWavs() {
        assertThrows(WavReader.WavException::class.java) { WavReader.read(ByteArray(10)) }
        assertThrows(WavReader.WavException::class.java) { WavReader.read(wav16(48000 * 3, 1, 48000)) }
        assertThrows(WavReader.WavException::class.java) { WavReader.read(wav16(100, 3, 48000)) }
        val corrupt = wav16(100, 1, 48000).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(40, Int.MAX_VALUE) }
        assertThrows(WavReader.WavException::class.java) { WavReader.read(corrupt) }
    }
}
