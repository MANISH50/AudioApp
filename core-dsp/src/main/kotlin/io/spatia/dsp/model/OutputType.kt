package io.spatia.dsp.model

import kotlinx.serialization.Serializable

@Serializable
enum class OutputType { SPEAKER, WIRED, BLUETOOTH, USB, HDMI_CAST }

/**
 * Safety envelope for one output type. Values are deliberately conservative: headphones put the transducer at the
 * ear, so they get the smallest boost budget and the earliest volume warning.
 */
data class OutputSafety(
    /** Maximum net positive gain (dB) the whole chain may apply anywhere in the spectrum. */
    val maxBoostDb: Float,
    /** Final limiter ceiling in dBFS. */
    val limiterCeilingDb: Float,
    /** Fraction of the system media volume (0..1) above which we show the high-volume warning. */
    val volumeWarningFraction: Float,
    val isHeadphones: Boolean,
) {
    companion object {
        fun forType(type: OutputType): OutputSafety = when (type) {
            OutputType.SPEAKER -> OutputSafety(6f, -1f, 0.85f, isHeadphones = false)
            OutputType.WIRED -> OutputSafety(3f, -1f, 0.60f, isHeadphones = true)
            OutputType.BLUETOOTH -> OutputSafety(4f, -1f, 0.60f, isHeadphones = true)
            OutputType.USB -> OutputSafety(3f, -1f, 0.60f, isHeadphones = true)
            OutputType.HDMI_CAST -> OutputSafety(3f, -1f, 0.90f, isHeadphones = false)
        }
    }
}
