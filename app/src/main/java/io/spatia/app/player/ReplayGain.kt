package io.spatia.app.player

/**
 * Reads track gain from ReplayGain (REPLAYGAIN_TRACK_GAIN / ALBUM) or Opus R128 tags. Values are untrusted tag text:
 * strictly parsed and clamped to ±15 dB.
 */
object ReplayGain {
    private val rgRx = Regex("""^\s*([+-]?\d{1,2}(?:\.\d{1,3})?)\s*(?:dB)?\s*$""", RegexOption.IGNORE_CASE)
    private val r128Rx = Regex("""^\s*([+-]?\d{1,6})\s*$""")

    /** [tags]: upper-cased key -> value. Prefers album gain when [preferAlbum]. */
    fun gainDb(tags: Map<String, String>, preferAlbum: Boolean = false): Float? {
        val order = if (preferAlbum) listOf("ALBUM", "TRACK") else listOf("TRACK", "ALBUM")
        for (kind in order) {
            tags["REPLAYGAIN_${kind}_GAIN"]?.let { v -> rgRx.matchEntire(v)?.let { return clamp(it.groupValues[1].toFloat()) } }
            tags["R128_${kind}_GAIN"]?.let { v ->
                // Q7.8 fixed point relative to -23 LUFS; ReplayGain reference is ~-18 LUFS, hence +5 dB.
                r128Rx.matchEntire(v)?.let { return clamp(it.groupValues[1].toInt() / 256f + 5f) }
            }
        }
        return null
    }

    private fun clamp(v: Float) = if (v.isFinite()) v.coerceIn(-15f, 15f) else 0f
}
