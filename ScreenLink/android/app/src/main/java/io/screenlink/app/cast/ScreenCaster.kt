package io.screenlink.app.cast

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import io.screenlink.app.net.LinkClient
import io.screenlink.app.net.Messages

/**
 * Screen → H.264 → laptop. One [VirtualDisplay] for the whole session (Android 14+ allows only one per consent); on
 * rotation the encoder is rebuilt at the new size and the display is resized onto the new encoder surface. Never
 * blocks on the network: when the send queue is full, frames are dropped until the next keyframe.
 */
class ScreenCaster(
    private val projection: MediaProjection,
    private val link: LinkClient,
    private val quality: Quality,
    private val screenSize: () -> Pair<Int, Int>,
    private val densityDpi: Int,
    private val onFatal: (String) -> Unit,
) {
    private val thread = HandlerThread("screenlink-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var size: VideoSize? = null
    @Volatile private var waitingForKey = true
    @Volatile private var released = false
    private var csd: ByteArray? = null

    fun start() = handler.post { reconfigure() }

    /** Re-checks the screen size (rotation, folding) and rebuilds the encoder if it changed. */
    fun onScreenChanged() = handler.post { if (!released) reconfigure() }

    fun requestKeyframe() = handler.post {
        if (released) return@post
        waitingForKey = true
        csd?.let { link.offerVideoConfig(configPayload(it)) }
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        val done = Object()
        handler.post {
            released = true
            display?.release(); display = null
            releaseCodec()
            synchronized(done) { done.notifyAll() }
        }
        synchronized(done) { runCatching { done.wait(1000) } }
        thread.quitSafely()
    }

    private fun configPayload(c: ByteArray): ByteArray {
        val s = size ?: VideoSize(0, 0)
        return Messages.videoConfig(s.width, s.height, c)
    }

    private fun reconfigure() {
        val (w, h) = screenSize()
        val target = supportedSize(w, h) ?: run { onFatal("no encoder"); return }
        if (target == size && codec != null) return
        try {
            display?.surface = null
            releaseCodec()
            val (newCodec, newSurface) = createEncoder(target)
            codec = newCodec
            surface = newSurface
            size = target
            csd = null
            waitingForKey = true
            val d = display
            if (d == null) {
                display = projection.createVirtualDisplay(
                    "ScreenLink", target.width, target.height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newSurface, null, handler,
                ) ?: run { onFatal("virtual display"); return }
            } else {
                d.resize(target.width, target.height, densityDpi)
                d.surface = newSurface
            }
            Log.i(TAG, "encoding ${target.width}x${target.height}")
        } catch (e: Exception) {
            // IllegalStateException / MediaCodec.CodecException / SecurityException (projection already stopped)
            Log.e(TAG, "encoder setup failed", e)
            onFatal(e.javaClass.simpleName)
        }
    }

    private fun encoderInfo(): MediaCodecInfo? =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, ignoreCase = true) } }
            .sortedByDescending { it.isHardwareAccelerated }
            .firstOrNull()

    private fun supportedSize(w: Int, h: Int): VideoSize? {
        val caps = encoderInfo()?.getCapabilitiesForType(MIME)?.videoCapabilities ?: return null
        var edge = quality.longEdge
        repeat(6) {
            val s = VideoSize.fit(w, h, edge)
            if (caps.isSizeSupported(s.width, s.height)) return s
            edge = (edge * 0.8).toInt()
        }
        return null
    }

    private fun createEncoder(s: VideoSize): Pair<MediaCodec, Surface> {
        val info = encoderInfo() ?: throw IllegalStateException("no H.264 encoder")
        val caps = info.getCapabilitiesForType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, s.width, s.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, caps.videoCapabilities.bitrateRange.clamp(quality.bitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // A still screen produces no frames; repeat the last one so the laptop never waits on a stale picture.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
        }
        val c = MediaCodec.createByCodecName(info.name)
        try {
            c.setCallback(callback, handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surf = c.createInputSurface()
            c.start()
            return c to surf
        } catch (e: Exception) {
            c.release()
            throw e
        }
    }

    private fun releaseCodec() {
        val c = codec
        codec = null
        if (c != null) {
            runCatching { c.stop() }
            c.release()
        }
        surface?.release()
        surface = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // Surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (codec !== this@ScreenCaster.codec) return // a buffer from a codec we've already replaced
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    handleOutput(bytes, info)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "output after release: ${e.message}")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "codec error", e)
            if (codec === this@ScreenCaster.codec) onFatal("codec")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Some encoders report SPS/PPS here instead of as a CODEC_CONFIG buffer.
            val sps = format.getByteBuffer("csd-0")
            val pps = format.getByteBuffer("csd-1")
            if (sps != null && csd == null) {
                val a = ByteArray(sps.remaining()).also { sps.duplicate().get(it) }
                val b = pps?.let { p -> ByteArray(p.remaining()).also { p.duplicate().get(it) } } ?: ByteArray(0)
                setConfig(a + b)
            }
        }
    }

    private fun setConfig(c: ByteArray) {
        csd = c
        link.offerVideoConfig(configPayload(c))
        waitingForKey = true
    }

    private fun handleOutput(bytes: ByteArray, info: MediaCodec.BufferInfo) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            setConfig(bytes)
            return
        }
        if (csd == null) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (waitingForKey && !key) return
        if (link.offerVideoFrame(Messages.videoFrame(info.presentationTimeUs, key, bytes))) {
            if (key) waitingForKey = false
        } else {
            // Network can't keep up: skip to the next keyframe rather than adding delay.
            waitingForKey = true
            runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    private companion object {
        const val TAG = "ScreenLinkCast"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
