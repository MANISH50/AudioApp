package io.spatia.dsp.io

import io.spatia.dsp.filters.FilterType
import io.spatia.dsp.model.EqFilter
import io.spatia.dsp.model.SoundSettings

/**
 * Parses the plain-text parametric EQ format used by the open AutoEq project and EqualizerAPO, e.g.
 * ```
 * Preamp: -6.2 dB
 * Filter 1: ON PK Fc 105 Hz Gain 3.4 dB Q 0.70
 * Filter 2: ON LSC Fc 105 Hz Gain 2.0 dB Q 0.70
 * ```
 * Untrusted input: size, line count and every numeric field are bounded; unknown lines are reported, not executed.
 */
object ParametricEqParser {
    const val MAX_BYTES = 16 * 1024
    private const val MAX_LINE = 256

    data class Result(val preampDb: Float, val filters: List<EqFilter>, val warnings: List<String>)

    class ParseException(message: String) : Exception(message)

    private val preampRx = Regex("""^Preamp:\s*(-?\d{1,3}(?:\.\d{1,4})?)\s*dB$""", RegexOption.IGNORE_CASE)
    private val filterRx = Regex(
        """^Filter\s*\d{0,3}:\s*(ON|OFF)\s+(PK|PEQ|LSC|LS|HSC|HS|LP|LPQ|HP|HPQ)\s+Fc\s+(\d{1,5}(?:\.\d{1,4})?)\s*Hz""" +
            """(?:\s+Gain\s+(-?\d{1,3}(?:\.\d{1,4})?)\s*dB)?(?:\s+Q\s+(\d{1,2}(?:\.\d{1,4})?))?$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(bytes: ByteArray): Result {
        if (bytes.size > MAX_BYTES) throw ParseException("File is larger than ${MAX_BYTES / 1024} KB")
        val text = bytes.decodeToString().removePrefix("﻿")
        if (text.any { it == '\u0000' }) throw ParseException("Not a text file")
        return parse(text)
    }

    fun parse(text: String): Result {
        var preamp = 0f
        val filters = mutableListOf<EqFilter>()
        val warnings = mutableListOf<String>()
        text.lineSequence().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (line.length > MAX_LINE) { warnings += "Line ${i + 1}: too long, skipped"; return@forEachIndexed }
            preampRx.matchEntire(line)?.let {
                preamp = it.groupValues[1].toFloat().coerceIn(-30f, 0f)
                return@forEachIndexed
            }
            val m = filterRx.matchEntire(line)
            if (m == null) { warnings += "Line ${i + 1}: not understood, skipped"; return@forEachIndexed }
            if (m.groupValues[1].equals("OFF", true)) return@forEachIndexed
            if (filters.size >= SoundSettings.MAX_CORRECTION_FILTERS) {
                warnings += "Only the first ${SoundSettings.MAX_CORRECTION_FILTERS} filters are used"; return@forEachIndexed
            }
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> FilterType.PEAK
                "LSC", "LS" -> FilterType.LOW_SHELF
                "HSC", "HS" -> FilterType.HIGH_SHELF
                "LP", "LPQ" -> FilterType.LOW_PASS
                else -> FilterType.HIGH_PASS
            }
            val fc = m.groupValues[3].toFloat()
            if (fc < 10f || fc > 22000f) { warnings += "Line ${i + 1}: frequency out of range, skipped"; return@forEachIndexed }
            val gain = m.groupValues[4].ifEmpty { "0" }.toFloat()
            val q = m.groupValues[5].ifEmpty { "0.707" }.toFloat()
            if (gain < -30f || gain > 20f || q < 0.1f || q > 20f) {
                warnings += "Line ${i + 1}: gain or Q out of range, clamped"
            }
            filters += EqFilter(type, fc, gain.coerceIn(-30f, 20f), q.coerceIn(0.1f, 20f))
        }
        if (filters.isEmpty()) throw ParseException("No filters found")
        return Result(preamp, filters, warnings)
    }
}
