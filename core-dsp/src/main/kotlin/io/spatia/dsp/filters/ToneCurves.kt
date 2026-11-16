package io.spatia.dsp.filters

import io.spatia.dsp.model.SoundSettings
import kotlin.math.max

/** Builds the filter list for every EQ-like stage from user settings. Pure, so it is easy to test and to plot. */
object ToneCurves {
    fun graphicEq(gains: List<Float>): List<FilterSpec> = SoundSettings.EQ_BANDS.toList().mapIndexedNotNull { i, f ->
        val g = gains.getOrElse(i) { 0f }
        if (g == 0f) null else when (i) {
            0 -> FilterSpec(FilterType.LOW_SHELF, f * 1.4f, g, 0.707f)
            SoundSettings.EQ_BANDS.lastIndex -> FilterSpec(FilterType.HIGH_SHELF, f * 0.7f, g, 0.707f)
            else -> FilterSpec(FilterType.PEAK, f, g, 1.41f) // one-octave bandwidth
        }
    }

    fun tone(bassDb: Float, trebleDb: Float): List<FilterSpec> = buildList {
        if (bassDb != 0f) add(FilterSpec(FilterType.LOW_SHELF, 100f, bassDb, 0.707f))
        if (trebleDb != 0f) add(FilterSpec(FilterType.HIGH_SHELF, 8000f, trebleDb, 0.707f))
    }

    fun voiceClarity(amount: Float): List<FilterSpec> = if (amount <= 0f) emptyList() else listOf(
        FilterSpec(FilterType.HIGH_PASS, 80f, 0f, 0.707f),
        FilterSpec(FilterType.LOW_SHELF, 200f, -4f * amount, 0.707f),
        FilterSpec(FilterType.PEAK, 2500f, 6f * amount, 1.0f),
    )

    /**
     * Equal-loudness compensation. At lower listening levels the ear loses sensitivity at the frequency extremes
     * (ISO 226:2003); the boost grows with attenuation below the reference level. Monotonic in [attenuationDb].
     */
    fun loudnessCompensation(attenuationDb: Float): List<FilterSpec> {
        val att = max(0f, attenuationDb)
        val bass = minOf(10f, att * 0.25f)
        val treble = minOf(4f, att * 0.08f)
        if (bass == 0f && treble == 0f) return emptyList()
        return listOf(
            FilterSpec(FilterType.LOW_SHELF, 120f, bass, 0.707f),
            FilterSpec(FilterType.HIGH_SHELF, 10000f, treble, 0.707f),
        )
    }

    /** Hearing-profile compensation: one peak per audiogram frequency. */
    fun hearing(gains: List<Float>): List<FilterSpec> =
        SoundSettings.HEARING_FREQS.toList().mapIndexedNotNull { i, f ->
            val g = gains.getOrElse(i) { 0f }
            if (g == 0f) null else FilterSpec(FilterType.PEAK, f, g, 1.2f)
        }

    fun correction(s: SoundSettings): List<FilterSpec> =
        if (!s.correctionEnabled) emptyList() else s.correction.map { FilterSpec(it.type, it.freqHz, it.gainDb, it.q) }

    /** Maximum boost (dB) of a filter cascade across the audible band, sampled on a log grid. */
    fun maxBoostDb(specs: List<FilterSpec>, sampleRate: Int, extraGainDb: Float = 0f): Float {
        if (specs.isEmpty()) return extraGainDb
        val coeffs = specs.map { it.coeffs(sampleRate) }
        var worst = Float.NEGATIVE_INFINITY
        var f = 20.0
        while (f <= minOf(20000.0, sampleRate * 0.45)) {
            val sum = coeffs.sumOf { it.magnitudeDb(f, sampleRate.toDouble()) }.toFloat() + extraGainDb
            if (sum > worst) worst = sum
            f *= 1.05
        }
        return worst
    }
}
