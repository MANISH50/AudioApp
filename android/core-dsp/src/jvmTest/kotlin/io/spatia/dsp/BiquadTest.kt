package io.spatia.dsp

import io.spatia.dsp.filters.Biquad
import io.spatia.dsp.filters.BiquadCoeffs
import io.spatia.dsp.filters.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiquadTest {
    private val sr = 48000.0

    @Test fun peakHasRequestedGainAtCentreAndUnityFarAway() {
        val c = BiquadCoeffs.design(FilterType.PEAK, 1000.0, 1.41, 6.0, sr)
        assertEquals(6.0, c.magnitudeDb(1000.0, sr), 0.05)
        assertEquals(0.0, c.magnitudeDb(20.0, sr), 0.1)
        assertEquals(0.0, c.magnitudeDb(18000.0, sr), 0.1)
    }

    @Test fun shelvesReachTheirGainAtTheExtremes() {
        val low = BiquadCoeffs.design(FilterType.LOW_SHELF, 100.0, 0.707, -8.0, sr)
        assertEquals(-8.0, low.magnitudeDb(20.0, sr), 0.3)
        assertEquals(0.0, low.magnitudeDb(5000.0, sr), 0.1)
        val high = BiquadCoeffs.design(FilterType.HIGH_SHELF, 8000.0, 0.707, 5.0, sr)
        assertEquals(5.0, high.magnitudeDb(20000.0, sr), 0.4)
    }

    @Test fun timeDomainMatchesAnalyticResponse() {
        val c = BiquadCoeffs.design(FilterType.PEAK, 1000.0, 1.0, 9.0, sr)
        val bq = Biquad(c)
        val x = TestSignals.sineStereo(1000.0, 48000, 48000, 0.1f)
        val mono = FloatArray(48000) { bq.process(x[it * 2]) }
        val gainDb = 20 * kotlin.math.log10(TestSignals.rms(mono, 24000, 48000) / TestSignals.rms(x, 48000, 96000))
        assertEquals(9.0, gainDb, 0.1)
    }

    @Test fun nonFiniteInputResetsInsteadOfPoisoningState() {
        val bq = Biquad(BiquadCoeffs.design(FilterType.PEAK, 1000.0, 1.0, 6.0, sr))
        bq.process(Float.NaN)
        val y = bq.process(0.5f)
        assertTrue(y.isFinite())
    }
}
