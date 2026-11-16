package io.spatia.dsp.model

import io.spatia.dsp.filters.FilterType
import io.spatia.dsp.safeClamp
import kotlinx.serialization.Serializable

@Serializable
data class EqFilter(val type: FilterType, val freqHz: Float, val gainDb: Float, val q: Float)

/**
 * Every user-adjustable parameter. Always pass through [validated] before use: values may come from disk, an
 * imported file or the UI, and none of those are trusted.
 */
@Serializable
data class SoundSettings(
    val mode: Mode = Mode.MUSIC,
    val eqGainsDb: List<Float> = List(EQ_BANDS.size) { 0f },
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val preampDb: Float = 0f,
    val loudnessNormalization: Boolean = false,
    val voiceClarity: Float = 0f,
    val spatialEnabled: Boolean = false,
    val width: Float = 1f,
    val roomSize: Float = 0f,
    val headTracking: Boolean = false,
    val nightCompression: Boolean = false,
    val crossfeed: Float = 0f,
    val psychoBass: Float = 0f,
    val loudnessCompensation: Boolean = false,
    val surroundUpmix: Boolean = false,
    val mono: Boolean = false,
    val balance: Float = 0f,
    val correction: List<EqFilter> = emptyList(),
    val correctionPreampDb: Float = 0f,
    val correctionEnabled: Boolean = false,
    val hearingCompensation: List<Float> = emptyList(),
    val hearingEnabled: Boolean = false,
    val convolutionEnabled: Boolean = false,
    val convolutionMix: Float = 0.3f,
    val bypass: Boolean = false,
) {
    fun validated(): SoundSettings = copy(
        eqGainsDb = List(EQ_BANDS.size) { i -> eqGainsDb.getOrElse(i) { 0f }.safeClamp(-12f, 12f, 0f) },
        bassDb = bassDb.safeClamp(-12f, 12f, 0f),
        trebleDb = trebleDb.safeClamp(-12f, 12f, 0f),
        preampDb = preampDb.safeClamp(-24f, 6f, 0f),
        voiceClarity = voiceClarity.safeClamp(0f, 1f, 0f),
        width = width.safeClamp(0f, 2f, 1f),
        roomSize = roomSize.safeClamp(0f, 1f, 0f),
        crossfeed = crossfeed.safeClamp(0f, 1f, 0f),
        psychoBass = psychoBass.safeClamp(0f, 1f, 0f),
        balance = balance.safeClamp(-1f, 1f, 0f),
        correction = correction.take(MAX_CORRECTION_FILTERS).mapNotNull { f ->
            val freq = f.freqHz.safeClamp(10f, 22000f, Float.NaN)
            if (freq.isNaN()) null else f.copy(
                freqHz = freq,
                gainDb = f.gainDb.safeClamp(-30f, 20f, 0f),
                q = f.q.safeClamp(0.1f, 20f, 0.707f),
            )
        },
        correctionPreampDb = correctionPreampDb.safeClamp(-30f, 0f, 0f),
        hearingCompensation = if (hearingCompensation.size == HEARING_FREQS.size)
            hearingCompensation.map { it.safeClamp(0f, MAX_HEARING_BOOST_DB, 0f) } else emptyList(),
        convolutionMix = convolutionMix.safeClamp(0f, 1f, 0.3f),
    )

    companion object {
        /** ISO octave centres for the 10-band graphic EQ. */
        val EQ_BANDS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val HEARING_FREQS = floatArrayOf(250f, 500f, 1000f, 2000f, 3000f, 4000f, 6000f, 8000f)
        const val MAX_CORRECTION_FILTERS = 20
        const val MAX_HEARING_BOOST_DB = 6f
    }
}
