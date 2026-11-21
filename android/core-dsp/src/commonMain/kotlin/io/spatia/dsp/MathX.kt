package io.spatia.dsp

import kotlin.math.PI

/** Multiplatform replacements for java.lang.Math helpers. */
internal fun toRadians(deg: Double): Double = deg * PI / 180.0
internal fun toDegrees(rad: Double): Double = rad * 180.0 / PI
