package io.spatia.dsp.analysis

import io.spatia.dsp.model.SoundSettings

/**
 * Turns per-ear listening-test thresholds into a gentle compensation curve. Uses the conservative "half-gain"
 * rule on the better-averaged ear relative to the user's own best threshold, capped at
 * [SoundSettings.MAX_HEARING_BOOST_DB]. Not a medical test; the UI says so.
 */
object HearingProfile {
    /** [leftDb]/[rightDb]: lowest audible tone level per frequency in dBFS (higher = worse hearing there). */
    fun compensation(leftDb: List<Float>, rightDb: List<Float>): List<Float> {
        val n = SoundSettings.HEARING_FREQS.size
        require(leftDb.size == n && rightDb.size == n) { "Need $n thresholds per ear" }
        val avg = List(n) { (leftDb[it] + rightDb[it]) / 2f }
        val best = avg.minOrNull() ?: 0f
        return avg.map { ((it - best) * 0.5f).coerceIn(0f, SoundSettings.MAX_HEARING_BOOST_DB) }
    }
}
