package io.spatia.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Decibel helpers. All gain math in the app goes through these to keep conventions in one place. */
object Db {
    const val SILENCE_DB = -120f

    fun toLinear(db: Float): Float = 10f.pow(db / 20f)

    fun fromLinear(linear: Float): Float =
        if (linear <= 1e-6f) SILENCE_DB else max(SILENCE_DB, 20f * log10(linear))
}

internal fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

/** Clamp that also rejects NaN/Infinity, which is how untrusted input usually breaks DSP. */
fun Float.safeClamp(min: Float, max: Float, fallback: Float = min): Float =
    finiteOr(fallback).coerceIn(min, max)
