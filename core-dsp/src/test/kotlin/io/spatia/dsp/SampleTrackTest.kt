package io.spatia.dsp

import io.spatia.dsp.io.WavReader
import io.spatia.dsp.io.WavWriter
import io.spatia.dsp.sample.SampleTrack
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleTrackTest {
    private val sr = 44100
    private val track = SampleTrack.render(sr)

    private fun peak(from: Double, to: Double): Float {
        var p = 0f
        for (i in (from * sr).toInt() until (to * sr).toInt()) p = maxOf(p, abs(track[0][i]), abs(track[1][i]))
        return p
    }

    private fun rms(ch: FloatArray, from: Double, to: Double): Double {
        var s = 0.0; val a = (from * sr).toInt(); val b = (to * sr).toInt()
        for (i in a until b) s += ch[i] * ch[i]
        return kotlin.math.sqrt(s / (b - a))
    }

    @Test fun stereoCorrectLengthAndHeadroom() {
        assertEquals(2, track.size)
        assertEquals((SampleTrack.LENGTH_S * sr).toInt(), track[0].size)
        assertEquals(Db.toLinear(SampleTrack.PEAK_DBFS), peak(0.0, SampleTrack.LENGTH_S), 1e-4f)
        assertTrue(track.all { ch -> ch.all { it.isFinite() } })
    }

    @Test fun isDeterministic() {
        val again = SampleTrack.render(sr)
        assertArrayEquals(track[0], again[0], 0f)
        assertArrayEquals(track[1], again[1], 0f)
    }

    @Test fun everySectionHasSound() {
        assertTrue(peak(0.0, SampleTrack.VOICE_START_S) > 0.1f)
        assertTrue(peak(SampleTrack.VOICE_START_S, SampleTrack.HITS_START_S - 0.5) > 0.05f)
        SampleTrack.HIT_TIMES_S.forEach { assertTrue(peak(it, it + SampleTrack.HIT_LENGTH_S) > 0.05f) }
        assertTrue(peak(SampleTrack.PAN_START_S, SampleTrack.LENGTH_S) > 0.05f)
    }

    @Test fun hitsAreSeparatedBySilenceSoRoomTailsAreAudible() {
        val gaps = SampleTrack.HIT_TIMES_S.map { it + SampleTrack.HIT_LENGTH_S } zip
            (SampleTrack.HIT_TIMES_S.drop(1) + SampleTrack.PAN_START_S)
        for ((a, b) in gaps) assertEquals("gap $a..$b", 0f, peak(a, b), 1e-6f)
        assertEquals(0f, peak(SampleTrack.HITS_START_S - 0.05, SampleTrack.HITS_START_S), 1e-6f)
    }

    @Test fun sweepSectionMovesBetweenEars() {
        // Pan follows a 2 s sine: first half-second leans right, the second second leans left.
        val l1 = rms(track[0], 20.1, 20.9); val r1 = rms(track[1], 20.1, 20.9)
        val l2 = rms(track[0], 21.1, 21.9); val r2 = rms(track[1], 21.1, 21.9)
        assertTrue("right first: $l1 vs $r1", r1 > l1 * 2)
        assertTrue("then left: $l2 vs $r2", l2 > r2 * 2)
    }

    @Test fun wavRoundTrip() {
        val clip = arrayOf(track[0].copyOfRange(0, sr), track[1].copyOfRange(0, sr))
        val wav = WavReader.read(WavWriter.write16(clip, sr))
        assertEquals(sr, wav.sampleRate)
        for (i in 0 until sr step 97) {
            assertEquals(clip[0][i], wav.channels[0][i], 1f / 16000)
            assertEquals(clip[1][i], wav.channels[1][i], 1f / 16000)
        }
    }
}
