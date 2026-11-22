package io.screenlink.app.control

import io.screenlink.app.net.Messages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A point in screen fractions (0..1). */
data class Pt(val x: Float, val y: Float)

/** One finger stroke to perform: a single point is a tap or long press, several points are a swipe or drag. */
data class Stroke(val points: List<Pt>, val durationMs: Long)

/**
 * Turns the laptop's mouse events (down / move / up, in 0..65535 picture coordinates) into finger strokes. The gesture
 * is performed when the mouse button is released, with the real timing: a quick click is a tap, holding still is a
 * long press, moving is a swipe or drag. Pure logic, unit-tested.
 */
class GestureBuilder(private val clock: () -> Long) {
    private val points = ArrayList<Pt>()
    private var downAt = -1L

    fun onPointer(action: Int, x: Int, y: Int): Stroke? {
        val p = Pt(norm(x), norm(y))
        when (action) {
            Messages.POINTER_DOWN -> {
                points.clear()
                points += p
                downAt = clock()
            }
            Messages.POINTER_MOVE -> if (downAt >= 0) {
                if (points.size >= MAX_POINTS) thin()
                points += p
            }
            Messages.POINTER_UP -> {
                if (downAt < 0) return null
                points += p
                val duration = clock() - downAt
                downAt = -1
                return finish(duration)
            }
            else -> { points.clear(); downAt = -1 }
        }
        return null
    }

    private fun thin() {
        val kept = points.filterIndexed { i, _ -> i % 2 == 0 }
        points.clear()
        points.addAll(kept)
    }

    private fun finish(duration: Long): Stroke {
        val first = points.first()
        val travel = points.maxOf { max(abs(it.x - first.x), abs(it.y - first.y)) }
        val stroke = when {
            travel < TAP_SLOP && duration >= LONG_PRESS_MS -> Stroke(listOf(first), min(duration, MAX_LONG_PRESS_MS))
            travel < TAP_SLOP -> Stroke(listOf(first), TAP_MS)
            else -> Stroke(points.toList(), duration.coerceIn(MIN_SWIPE_MS, MAX_STROKE_MS))
        }
        points.clear()
        return stroke
    }

    companion object {
        const val TAP_SLOP = 0.02f
        const val TAP_MS = 50L
        const val LONG_PRESS_MS = 500L
        const val MAX_LONG_PRESS_MS = 3_000L
        const val MIN_SWIPE_MS = 80L
        const val MAX_STROKE_MS = 10_000L
        const val MAX_POINTS = 400

        fun norm(v: Int): Float = (v.coerceIn(0, 65535) / 65535f).coerceIn(0.002f, 0.998f)

        /**
         * Mouse wheel → a short swipe at the pointer. Wheel up (positive) shows content above, which is a finger
         * moving down. Each 120 is one notch; at most 3 notches per swipe.
         */
        fun scroll(x: Int, y: Int, delta: Int): Stroke? {
            if (delta == 0) return null
            val notches = min(3f, abs(delta) / 120f).coerceAtLeast(1f)
            val start = Pt(norm(x), norm(y))
            val dy = 0.12f * notches * if (delta > 0) 1 else -1
            val endY = (start.y + dy).coerceIn(0.002f, 0.998f)
            return Stroke(listOf(start, Pt(start.x, endY)), 250L)
        }

        /** Fraction → pixel, kept inside the screen. */
        fun toPixels(p: Pt, width: Int, height: Int): Pair<Float, Float> =
            (p.x * width).coerceIn(0f, width - 1f) to (p.y * height).coerceIn(0f, height - 1f)
    }
}
