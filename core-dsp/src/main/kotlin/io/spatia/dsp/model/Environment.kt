package io.spatia.dsp.model

import kotlinx.serialization.Serializable
import kotlin.math.max

/** Acoustic spaces the listener can "place" the music in. Applied on top of the original audio (send effect). */
@Serializable
enum class Environment {
    OFF, HALL, CINEMA, PARK, BATHROOM, ECHOING, CATHEDRAL, STADIUM, CLUB, LIVING_ROOM, TUNNEL;

    val preset: EnvironmentPreset? get() = EnvironmentPresets.forEnvironment(this)
}

/** One early reflection: arrival time after the direct sound and its level in each ear. */
data class Reflection(val timeMs: Float, val gainL: Float, val gainR: Float)

/**
 * Designed character of a space at the default (50 %) intensity. Pure data so it can be unit-tested and tuned.
 */
data class EnvironmentPreset(
    val preDelayMs: Float,
    val reflections: List<Reflection>,
    val reflectionGain: Float,
    /** Late-reverb decay time (s) at low/mid frequencies. */
    val rt60: Float,
    /** Scales the reverb network's delay lengths: bigger = sparser, larger-sounding room. 0.3..2. */
    val size: Float,
    /** High-frequency absorption inside the tail, 0 = hard tiles, 0.7 = curtains/seats/people. */
    val damping: Float,
    val tailGain: Float,
    /** Discrete repeating echo (canyon, rear-wall slap, flutter). echoGain 0 = none. */
    val echoMs: Float = 0f,
    val echoFeedback: Float = 0f,
    val echoGain: Float = 0f,
    /** Band-limits applied to the effect only (never to the original audio). */
    val lowCutHz: Float,
    val highCutHz: Float,
    val width: Float,
    /** Input diffusion (allpass coefficient): high = smooth wash, low = grainy/metallic. */
    val diffusion: Float,
) {
    /** Resolve the intensity meter: 0 = effect silent, 0.5 = designed default, 1 = strongest. */
    fun scaled(intensity: Float): ScaledEnvironment {
        val i = if (intensity.isFinite()) intensity.coerceIn(0f, 1f) else 0.5f
        val wet = if (i <= 0.5f) i / 0.5f else 1f + (i - 0.5f) / 0.5f // 0 .. 1 .. 2 (+6 dB)
        val decay = if (i <= 0.5f) 0.6f + 0.8f * i else 1f + (i - 0.5f)  // 0.6 .. 1.0 .. 1.5
        return ScaledEnvironment(
            wetGain = wet,
            rt60 = max(0.1f, rt60 * decay),
            echoFeedback = (echoFeedback * decay).coerceIn(0f, MAX_ECHO_FEEDBACK),
        )
    }

    companion object {
        /** Hard cap so echoes always die away and can never build up into feedback. */
        const val MAX_ECHO_FEEDBACK = 0.85f
        const val DEFAULT_INTENSITY = 0.5f
    }
}

data class ScaledEnvironment(val wetGain: Float, val rt60: Float, val echoFeedback: Float)

object EnvironmentPresets {
    /**
     * Deterministic, slightly irregular reflection pattern between [firstMs] and [lastMs]. Levels fall with arrival
     * time (1/distance) and alternate between ears so the room surrounds the listener.
     */
    fun pattern(count: Int, firstMs: Float, lastMs: Float, seed: Int): List<Reflection> {
        var s = seed.toLong() * 2654435761L + 1
        fun rnd(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) % 1000).toFloat() / 1000f }
        return List(count) { k ->
            val frac = if (count == 1) 0f else k / (count - 1f)
            val t = firstMs + (lastMs - firstMs) * (frac + (rnd() - 0.5f) * 0.6f / count).coerceIn(0f, 1f)
            val level = (firstMs / t).coerceIn(0.15f, 1f) * (0.75f + 0.25f * rnd())
            val pan = if (k % 2 == 0) 0.35f + 0.3f * rnd() else -(0.35f + 0.3f * rnd())
            Reflection(t, level * (1f - pan), level * (1f + pan))
        }.sortedBy { it.timeMs }
    }

    private val table: Map<Environment, EnvironmentPreset> = mapOf(
        // Empty concert hall: clear pre-delay, many side-wall reflections, long warm tail.
        Environment.HALL to EnvironmentPreset(
            preDelayMs = 25f, reflections = pattern(10, 15f, 90f, 1), reflectionGain = 0.5f,
            rt60 = 2.4f, size = 1.4f, damping = 0.35f, tailGain = 0.55f,
            lowCutHz = 150f, highCutHz = 9000f, width = 1.0f, diffusion = 0.7f,
        ),
        // Old single-screen cinema: band-limited "vintage" tail plus a slap back from the rear wall.
        Environment.CINEMA to EnvironmentPreset(
            preDelayMs = 20f, reflections = pattern(8, 10f, 60f, 2), reflectionGain = 0.45f,
            rt60 = 1.6f, size = 1.2f, damping = 0.5f, tailGain = 0.6f,
            echoMs = 90f, echoFeedback = 0.3f, echoGain = 0.4f,
            lowCutHz = 220f, highCutHz = 5000f, width = 0.8f, diffusion = 0.65f,
        ),
        // Open air: almost no reverb, a few ground/tree reflections and one distant building.
        Environment.PARK to EnvironmentPreset(
            preDelayMs = 8f, reflections = pattern(3, 20f, 60f, 3), reflectionGain = 0.2f,
            rt60 = 0.35f, size = 0.6f, damping = 0.6f, tailGain = 0.12f,
            echoMs = 180f, echoFeedback = 0.1f, echoGain = 0.18f,
            lowCutHz = 200f, highCutHz = 7000f, width = 1.2f, diffusion = 0.5f,
        ),
        // Small tiled room: dense, bright, immediate.
        Environment.BATHROOM to EnvironmentPreset(
            preDelayMs = 2f, reflections = pattern(12, 2f, 25f, 4), reflectionGain = 0.7f,
            rt60 = 0.9f, size = 0.35f, damping = 0.05f, tailGain = 0.55f,
            lowCutHz = 180f, highCutHz = 12000f, width = 0.7f, diffusion = 0.75f,
        ),
        // Canyon: clear repeating echoes bouncing left/right.
        Environment.ECHOING to EnvironmentPreset(
            preDelayMs = 10f, reflections = pattern(2, 30f, 60f, 5), reflectionGain = 0.15f,
            rt60 = 1.2f, size = 1.6f, damping = 0.5f, tailGain = 0.2f,
            echoMs = 320f, echoFeedback = 0.55f, echoGain = 0.55f,
            lowCutHz = 200f, highCutHz = 6000f, width = 1.2f, diffusion = 0.5f,
        ),
        Environment.CATHEDRAL to EnvironmentPreset(
            preDelayMs = 45f, reflections = pattern(10, 30f, 140f, 6), reflectionGain = 0.4f,
            rt60 = 5.0f, size = 2.0f, damping = 0.55f, tailGain = 0.6f,
            lowCutHz = 120f, highCutHz = 6500f, width = 1.1f, diffusion = 0.75f,
        ),
        Environment.STADIUM to EnvironmentPreset(
            preDelayMs = 30f, reflections = pattern(4, 30f, 120f, 7), reflectionGain = 0.25f,
            rt60 = 3.0f, size = 2.0f, damping = 0.5f, tailGain = 0.4f,
            echoMs = 250f, echoFeedback = 0.3f, echoGain = 0.35f,
            lowCutHz = 200f, highCutHz = 7000f, width = 1.3f, diffusion = 0.6f,
        ),
        Environment.CLUB to EnvironmentPreset(
            preDelayMs = 8f, reflections = pattern(8, 5f, 40f, 8), reflectionGain = 0.45f,
            rt60 = 0.7f, size = 0.7f, damping = 0.3f, tailGain = 0.4f,
            lowCutHz = 150f, highCutHz = 10000f, width = 0.9f, diffusion = 0.7f,
        ),
        Environment.LIVING_ROOM to EnvironmentPreset(
            preDelayMs = 4f, reflections = pattern(6, 3f, 25f, 9), reflectionGain = 0.3f,
            rt60 = 0.4f, size = 0.5f, damping = 0.5f, tailGain = 0.25f,
            lowCutHz = 150f, highCutHz = 9000f, width = 0.8f, diffusion = 0.6f,
        ),
        // Hard parallel walls: fast flutter echo and a ringing tail.
        Environment.TUNNEL to EnvironmentPreset(
            preDelayMs = 6f, reflections = pattern(6, 8f, 60f, 10), reflectionGain = 0.4f,
            rt60 = 1.8f, size = 0.8f, damping = 0.15f, tailGain = 0.45f,
            echoMs = 38f, echoFeedback = 0.45f, echoGain = 0.3f,
            lowCutHz = 180f, highCutHz = 8000f, width = 0.6f, diffusion = 0.35f,
        ),
    )

    fun forEnvironment(e: Environment): EnvironmentPreset? = table[e]
}
