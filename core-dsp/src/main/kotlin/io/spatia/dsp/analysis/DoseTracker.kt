package io.spatia.dsp.analysis

import kotlin.math.pow

/**
 * Weekly sound-dose estimate following the WHO/ITU H.870 safe-listening reference: 80 dB(A) for 40 h/week with a
 * 3 dB exchange rate. SPL is only an *estimate* (the app can't know headphone sensitivity), so the UI presents it
 * as guidance, never as a measurement.
 */
object DoseTracker {
    const val REFERENCE_DB = 80.0
    const val REFERENCE_HOURS = 40.0

    /** Fraction of the weekly allowance consumed by [seconds] at [splDb]. */
    fun doseFraction(splDb: Double, seconds: Double): Double {
        if (seconds <= 0 || !splDb.isFinite()) return 0.0
        val allowedHours = REFERENCE_HOURS / 2.0.pow((splDb - REFERENCE_DB) / 3.0)
        return seconds / 3600.0 / allowedHours
    }

    /**
     * Rough SPL estimate from system volume fraction and the processed RMS level. Assumes a typical headphone at
     * full volume and 0 dBFS RMS reaches ~[fullScaleSpl] dB SPL.
     */
    fun estimateSpl(volumeFraction: Float, rmsDbfs: Float, fullScaleSpl: Double = 100.0): Double {
        val v = volumeFraction.coerceIn(0f, 1f)
        if (v == 0f) return 0.0
        // Android media volume curves are roughly 60 dB across the range.
        val volDb = (v - 1f) * 60.0
        return fullScaleSpl + volDb + rmsDbfs
    }
}
