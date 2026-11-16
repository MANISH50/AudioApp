package io.spatia.dsp

/**
 * Linear per-sample ramp so that no parameter change produces a step (zipper noise / clicks).
 * Not thread-safe; owned by the audio thread. The UI thread hands new targets over through [DspChain].
 */
class ParamRamp(initial: Float, private var rampSamples: Int = 1024) {
    var current: Float = initial
        private set
    var target: Float = initial
        private set
    private var step = 0f
    private var remaining = 0

    init {
        require(rampSamples > 0)
    }

    fun setRampLength(samples: Int) {
        rampSamples = samples.coerceAtLeast(1)
    }

    fun setTarget(value: Float) {
        if (!value.isFinite() || value == target) return
        target = value
        remaining = rampSamples
        step = (target - current) / rampSamples
    }

    /** Jump without ramping (only for initialisation before audio starts). */
    fun snap(value: Float) {
        current = value; target = value; remaining = 0; step = 0f
    }

    val isRamping: Boolean get() = remaining > 0

    fun next(): Float {
        if (remaining > 0) {
            remaining--
            current = if (remaining == 0) target else current + step
        }
        return current
    }

    /** Advance [n] samples at once (used for block-rate parameters). */
    fun advance(n: Int): Float {
        if (remaining <= 0) return current
        val k = minOf(n, remaining)
        remaining -= k
        current = if (remaining == 0) target else current + step * k
        return current
    }
}
