package io.spatia.app.player

import android.media.MediaCodecList
import androidx.annotation.StringRes
import io.spatia.app.R

/** Decides up front whether a picked file can be played, so the user gets a clear message instead of a silent fail. */
object FormatSupport {
    sealed interface Verdict {
        data object Supported : Verdict
        data class Unsupported(@StringRes val reason: Int) : Verdict
    }

    private val supportedMime = setOf(
        "audio/flac", "audio/x-flac", "audio/opus", "audio/ogg", "application/ogg", "audio/aac", "audio/mp4",
        "audio/x-m4a", "audio/m4a", "audio/mp4a-latm", "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav",
        "audio/wave", "audio/vnd.wave",
    )
    private val supportedExt = setOf("flac", "opus", "ogg", "oga", "aac", "m4a", "mp4", "mp3", "wav")

    fun classify(mime: String?, displayName: String?, platformIamfDecoder: Boolean): Verdict {
        val m = mime?.lowercase()?.substringBefore(';')?.trim()
        val ext = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext == "iamf" || m == "audio/iamf") {
            // Raw IAMF sequences have no Media3 extractor; IAMF must arrive inside MP4.
            return if (ext == "iamf") Verdict.Unsupported(R.string.format_iamf_raw)
            else if (platformIamfDecoder) Verdict.Supported else Verdict.Unsupported(R.string.format_iamf_no_decoder)
        }
        if (m != null && m in supportedMime) return Verdict.Supported
        if (ext in supportedExt) return Verdict.Supported
        return when {
            ext in setOf("dsf", "dff", "ape", "wv", "wma", "alac", "aiff", "aif", "mka") ->
                Verdict.Unsupported(R.string.format_known_unsupported)
            m != null && !m.startsWith("audio/") && m != "application/ogg" -> Verdict.Unsupported(R.string.format_not_audio)
            else -> Verdict.Unsupported(R.string.format_unknown)
        }
    }

    /** True if the platform exposes an IAMF decoder (Android 16+ on some devices). */
    fun platformIamfDecoder(): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("audio/iamf", ignoreCase = true) }
        }
    } catch (e: RuntimeException) { false }

    fun hasDecoder(mime: String): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    } catch (e: RuntimeException) { false }
}
