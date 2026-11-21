package io.spatia.dsp.model

import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for settings exchanged with other runtimes (the Windows app talks to the engine in
 * JSON). Tolerant on input (unknown keys ignored, unknown enum values fall back to defaults), complete on output.
 */
val SpatiaJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}
