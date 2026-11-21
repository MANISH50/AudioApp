package io.screenlink.app.cast

import kotlin.math.max
import kotlin.math.roundToInt

enum class Quality(val longEdge: Int, val bitrate: Int) {
    STANDARD(1280, 6_000_000),
    HIGH(1920, 10_000_000),
}

data class VideoSize(val width: Int, val height: Int) {
    companion object {
        /**
         * Scales a screen of [screenW]×[screenH] so its long edge is at most [maxLongEdge], keeping the aspect ratio,
         * with both sides rounded down to a multiple of 16 (what hardware encoders handle everywhere).
         */
        fun fit(screenW: Int, screenH: Int, maxLongEdge: Int): VideoSize {
            require(screenW > 0 && screenH > 0 && maxLongEdge >= 16)
            val scale = minOf(1.0, maxLongEdge.toDouble() / max(screenW, screenH))
            fun r(v: Int) = max(16, ((v * scale).roundToInt() / 16) * 16)
            return VideoSize(r(screenW), r(screenH))
        }
    }
}
