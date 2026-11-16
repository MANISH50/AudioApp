package io.spatia.dsp.model

/** One-tap starting points. Custom starts from whatever the user last saved. */
object Presets {
    fun forMode(mode: Mode, base: SoundSettings = SoundSettings()): SoundSettings {
        val neutral = base.copy(
            mode = mode, eqGainsDb = List(10) { 0f }, bassDb = 0f, trebleDb = 0f,
            voiceClarity = 0f, nightCompression = false, loudnessNormalization = false,
            spatialEnabled = false, width = 1f, roomSize = 0f, surroundUpmix = false,
        )
        return when (mode) {
            Mode.MUSIC -> neutral.copy(
                eqGainsDb = listOf(2f, 2f, 1f, 0f, 0f, 0f, 0.5f, 1f, 1.5f, 1f),
                spatialEnabled = true, width = 1.2f, roomSize = 0.15f,
            )
            Mode.MOVIE -> neutral.copy(
                eqGainsDb = listOf(3f, 2.5f, 1f, 0f, -0.5f, 0f, 1f, 1.5f, 1f, 0.5f),
                voiceClarity = 0.4f, spatialEnabled = true, width = 1.5f, roomSize = 0.35f,
                surroundUpmix = true, loudnessNormalization = true,
            )
            Mode.GAMING -> neutral.copy(
                eqGainsDb = listOf(1f, 1f, 0f, -1f, 0f, 1f, 2f, 2.5f, 1.5f, 0f),
                spatialEnabled = true, width = 1.4f, roomSize = 0.1f,
            )
            Mode.VOICE -> neutral.copy(
                eqGainsDb = listOf(-6f, -4f, -2f, 0f, 1f, 2f, 3f, 2f, 0f, -2f),
                voiceClarity = 0.8f, loudnessNormalization = true, width = 0.8f,
            )
            Mode.NIGHT -> neutral.copy(
                eqGainsDb = listOf(-3f, -2f, -1f, 0f, 0f, 1f, 1.5f, 1f, 0f, -1f),
                voiceClarity = 0.5f, nightCompression = true, loudnessNormalization = true,
                loudnessCompensation = true,
            )
            Mode.CUSTOM -> base.copy(mode = Mode.CUSTOM)
        }.validated()
    }
}
