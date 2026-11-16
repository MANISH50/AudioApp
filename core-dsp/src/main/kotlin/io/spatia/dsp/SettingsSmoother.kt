package io.spatia.dsp

import io.spatia.dsp.model.SoundSettings

/**
 * Moves continuous parameters from [SoundSettings] toward their targets in bounded steps so the audio thread never
 * sees a jump. Non-continuous ("structural") differences are reported via [structureKey] and handled by a short
 * duck-and-swap in [DspChain].
 */
internal object SettingsSmoother {
    fun step(cur: SoundSettings, tgt: SoundSettings, dbStep: Float, unitStep: Float): SoundSettings {
        if (cur == tgt) return cur
        return tgt.copy(
            eqGainsDb = cur.eqGainsDb.zip(tgt.eqGainsDb) { c, t -> approach(c, t, dbStep) },
            bassDb = approach(cur.bassDb, tgt.bassDb, dbStep),
            trebleDb = approach(cur.trebleDb, tgt.trebleDb, dbStep),
            preampDb = approach(cur.preampDb, tgt.preampDb, dbStep),
            voiceClarity = approach(cur.voiceClarity, tgt.voiceClarity, unitStep),
            width = approach(cur.width, tgt.width, unitStep),
            roomSize = approach(cur.roomSize, tgt.roomSize, unitStep),
            crossfeed = approach(cur.crossfeed, tgt.crossfeed, unitStep),
            psychoBass = approach(cur.psychoBass, tgt.psychoBass, unitStep),
            balance = approach(cur.balance, tgt.balance, unitStep),
            convolutionMix = approach(cur.convolutionMix, tgt.convolutionMix, unitStep),
            correctionPreampDb = approach(cur.correctionPreampDb, tgt.correctionPreampDb, dbStep),
            correction = if (cur.correction.size == tgt.correction.size) cur.correction.zip(tgt.correction) { c, t ->
                t.copy(gainDb = approach(c.gainDb, t.gainDb, dbStep))
            } else tgt.correction,
            hearingCompensation = if (cur.hearingCompensation.size == tgt.hearingCompensation.size)
                cur.hearingCompensation.zip(tgt.hearingCompensation) { c, t -> approach(c, t, dbStep) }
            else tgt.hearingCompensation,
        )
    }

    /** Everything that changes the processing graph rather than a gain. */
    fun structureKey(s: SoundSettings): List<Any> = listOf(
        s.spatialEnabled, s.surroundUpmix, s.nightCompression, s.loudnessNormalization, s.mono,
        s.correctionEnabled, s.correction.map { Triple(it.type, it.freqHz, it.q) },
        s.hearingEnabled, s.hearingCompensation.size, s.loudnessCompensation, s.convolutionEnabled,
        s.voiceClarity > 0f, s.eqGainsDb.map { it != 0f }, s.bassDb != 0f, s.trebleDb != 0f,
    )

    private fun approach(c: Float, t: Float, step: Float): Float =
        if (c < t) minOf(t, c + step) else maxOf(t, c - step)
}
