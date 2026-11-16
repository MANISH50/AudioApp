package io.spatia.dsp

import io.spatia.dsp.model.OutputSafety
import kotlin.math.max
import kotlin.math.min

/**
 * Enforces the per-output gain budget. The chain's measured worst-case boost is offset by a negative pre-gain so the
 * net boost never exceeds [OutputSafety.maxBoostDb]; the limiter then guarantees the absolute ceiling.
 */
object SafetyPolicy {
    /** Pre-gain (<= 0 dB) to apply given the curve's maximum boost. */
    fun headroomGainDb(maxCurveBoostDb: Float, safety: OutputSafety): Float =
        min(0f, safety.maxBoostDb - max(0f, maxCurveBoostDb.finiteOr(0f)))

    /** Per-app boost requested by the user, capped. */
    fun capAppBoostDb(requestedDb: Float, safety: OutputSafety): Float =
        requestedDb.safeClamp(0f, safety.maxBoostDb, 0f)

    /** Loudness-normaliser make-up gain cap. */
    fun maxNormalizerGainDb(safety: OutputSafety): Float = safety.maxBoostDb

    fun shouldWarnVolume(volumeFraction: Float, safety: OutputSafety): Boolean =
        volumeFraction.finiteOr(1f) > safety.volumeWarningFraction
}
