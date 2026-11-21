package io.spatia.dsp.model

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Parameters for Android's platform EnvironmentalReverb (I3DL2 model), in the units and ranges that API accepts.
 * Used to give other apps' audio an approximation of Spatia's environments, since apps can't run their own DSP on
 * another app's samples.
 */
data class I3dl2Params(
    val roomLevel: Int,        // mB  [-9000, 0]
    val roomHFLevel: Int,      // mB  [-9000, 0]
    val decayTime: Int,        // ms  [100, 20000]
    val decayHFRatio: Int,     // permille [100, 2000]
    val reflectionsLevel: Int, // mB  [-9000, 1000]
    val reflectionsDelay: Int, // ms  [0, 300]
    val reverbLevel: Int,      // mB  [-9000, 2000]
    val reverbDelay: Int,      // ms  [0, 100]
    val diffusion: Int,        // permille [0, 1000]
    val density: Int,          // permille [0, 1000]
) {
    companion object {
        const val SILENT_MB = -9000
    }
}

/**
 * Maps a preset and the intensity meter to I3DL2. Returns null at zero intensity (effect off). The platform reverb
 * can't make discrete repeating echoes, so echo-based spaces use a long reflections delay as the closest match.
 */
fun EnvironmentPreset.toI3dl2(intensity: Float): I3dl2Params? {
    val sc = scaled(intensity)
    if (sc.wetGain <= 0f) return null
    val wetDb = 20f * log10(sc.wetGain)            // -inf .. +6
    val boostMb = (max(0f, wetDb) * 100).roundToInt()
    fun mb(gain: Float) = (20f * log10(gain.coerceAtLeast(1e-4f)) * 100).roundToInt()

    val hfCut = (12000f - highCutHz.coerceIn(2000f, 12000f)) / 7000f // 0 (bright) .. ~1.4 (dark)
    val firstReflection = reflections.minOfOrNull { it.timeMs } ?: 0f
    val lastReflection = reflections.maxOfOrNull { it.timeMs } ?: 0f
    val reflectionsDelay = if (echoGain > 0f) echoMs else preDelayMs + firstReflection
    return I3dl2Params(
        roomLevel = (wetDb * 100).roundToInt().coerceIn(I3dl2Params.SILENT_MB, 0),
        roomHFLevel = (-100 - hfCut * 1400).roundToInt().coerceIn(-9000, 0),
        decayTime = (sc.rt60 * 1000).roundToInt().coerceIn(100, 20000),
        decayHFRatio = ((1f - damping * 0.8f) * 1000).roundToInt().coerceIn(100, 2000),
        reflectionsLevel = (mb(reflectionGain + echoGain * 0.5f) + boostMb).coerceIn(-9000, 1000),
        reflectionsDelay = reflectionsDelay.roundToInt().coerceIn(0, 300),
        reverbLevel = (mb(tailGain) + boostMb).coerceIn(-9000, 2000),
        reverbDelay = ((lastReflection - firstReflection) / 2f + 5f).roundToInt().coerceIn(0, 100),
        diffusion = (diffusion / 0.8f * 1000).roundToInt().coerceIn(0, 1000),
        density = (1000 - size * 300).roundToInt().coerceIn(200, 1000),
    )
}
