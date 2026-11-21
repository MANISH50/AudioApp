package io.spatia.dsp.model

/** Pure decision logic for which output profile is active. Android-side code only feeds it device facts. */
object ProfileSelector {
    /** When several outputs are connected, Android routes media to the most "personal" one. */
    private val priority = listOf(
        OutputType.USB, OutputType.WIRED, OutputType.BLUETOOTH, OutputType.HDMI_CAST, OutputType.SPEAKER,
    )

    fun activeOutput(connected: Collection<OutputType>, manualOverride: OutputType?): OutputType {
        if (manualOverride != null) return manualOverride
        return priority.firstOrNull { it in connected } ?: OutputType.SPEAKER
    }

    /**
     * Settings for an output: a remembered per-device profile wins, then the per-type profile, then defaults.
     * The result is always validated.
     */
    fun settingsFor(
        output: OutputType,
        deviceKey: String?,
        perDevice: Map<String, SoundSettings>,
        perType: Map<OutputType, SoundSettings>,
    ): SoundSettings {
        val chosen = deviceKey?.let { perDevice[it] } ?: perType[output] ?: defaultFor(output)
        return chosen.validated()
    }

    fun defaultFor(output: OutputType): SoundSettings = when (output) {
        // Small speakers: no deep bass boost (driver protection), psychoacoustic bass instead.
        OutputType.SPEAKER -> Presets.forMode(Mode.MUSIC).copy(
            eqGainsDb = listOf(0f, 0f, 1f, 0f, 0f, 0f, 0.5f, 1f, 1f, 0f), psychoBass = 0.5f,
            spatialEnabled = false,
        )
        OutputType.WIRED, OutputType.USB, OutputType.BLUETOOTH -> Presets.forMode(Mode.MUSIC).copy(crossfeed = 0.3f)
        OutputType.HDMI_CAST -> Presets.forMode(Mode.MOVIE).copy(spatialEnabled = false, surroundUpmix = false)
    }
}
