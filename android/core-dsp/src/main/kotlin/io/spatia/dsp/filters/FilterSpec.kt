package io.spatia.dsp.filters

/** One user-level filter, independent of sample rate. */
data class FilterSpec(
    val type: FilterType,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float,
) {
    fun coeffs(sampleRate: Int) = BiquadCoeffs.design(type, freqHz.toDouble(), q.toDouble(), gainDb.toDouble(), sampleRate.toDouble())
}

/**
 * Cascade of biquads applied to N channels. Coefficients are swapped at block boundaries; callers ramp the
 * underlying gains so each swap is a small change (no audible step).
 */
class FilterBank(private val channels: Int) {
    private var stages: Array<Array<Biquad>> = emptyArray()
    private var specs: List<FilterSpec> = emptyList()

    fun setFilters(newSpecs: List<FilterSpec>, sampleRate: Int) {
        if (newSpecs.size != specs.size) {
            stages = Array(newSpecs.size) { Array(channels) { Biquad() } }
        }
        newSpecs.forEachIndexed { i, s ->
            val c = s.coeffs(sampleRate)
            for (ch in 0 until channels) stages[i][ch].coeffs = c
        }
        specs = newSpecs
    }

    /** Process interleaved [buf] of [frames] frames in place. */
    fun process(buf: FloatArray, frames: Int) {
        if (stages.isEmpty()) return
        for (st in stages) {
            var idx = 0
            for (f in 0 until frames) {
                for (ch in 0 until channels) {
                    buf[idx] = st[ch].process(buf[idx]); idx++
                }
            }
        }
    }

    fun reset() = stages.forEach { s -> s.forEach { it.reset() } }
}
