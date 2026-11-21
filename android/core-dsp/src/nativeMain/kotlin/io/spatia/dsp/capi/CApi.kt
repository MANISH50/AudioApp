@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.spatia.dsp.capi

import io.spatia.dsp.DspChain
import io.spatia.dsp.SafetyPolicy
import io.spatia.dsp.analysis.Meters
import io.spatia.dsp.filters.Convolver
import io.spatia.dsp.io.ParametricEqParser
import io.spatia.dsp.io.WavReader
import io.spatia.dsp.model.EqFilter
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.Presets
import io.spatia.dsp.model.ProfileSelector
import io.spatia.dsp.model.SoundSettings
import io.spatia.dsp.model.SpatiaJson
import io.spatia.dsp.sample.SampleTrack
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.Serializable

/*
 * C API of Spatia's engine (spatia_dsp.dll / libspatia_dsp.so), used by the Windows app via P/Invoke.
 * Rules: no exception ever crosses the boundary (errors become return codes or JSON errors); every input is
 * bounded and validated; every pointer returned by the library is freed by the caller with spatia_free.
 */

private const val MAX_JSON_BYTES = 256 * 1024
private const val MAX_FRAMES = 1 shl 16

/** Bumped when the C API changes incompatibly. */
@CName("spatia_api_version")
fun apiVersion(): Int = 1

private class ChainHandle(val chain: DspChain) {
    val meters = Meters(chain.sampleRate)
    var inBuf = FloatArray(0)
    var outBuf = FloatArray(0)
}

private fun handle(h: COpaquePointer?): ChainHandle? = h?.asStableRef<ChainHandle>()?.get()

private fun outputType(code: Int): OutputType? = OutputType.entries.getOrNull(code)

private fun cString(s: String): CPointer<ByteVar> {
    val bytes = s.encodeToByteArray()
    val p = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) p[i] = bytes[i]
    p[bytes.size] = 0
    return p
}

private fun readJson(p: CPointer<ByteVar>?): String? {
    if (p == null) return null
    var n = 0
    while (n <= MAX_JSON_BYTES && p[n] != 0.toByte()) n++
    if (n > MAX_JSON_BYTES) return null
    return p.toKString()
}

private fun decodeSettings(json: String?): SoundSettings? = try {
    json?.let { SpatiaJson.decodeFromString(SoundSettings.serializer(), it).validated() }
} catch (e: Exception) { null }

private fun encodeSettings(s: SoundSettings) = SpatiaJson.encodeToString(SoundSettings.serializer(), s)

/** Frees any pointer returned by this library. */
@CName("spatia_free")
fun free(p: COpaquePointer?) { if (p != null) nativeHeap.free(p.rawValue) }

// ---------------- processing chain ----------------

/** Returns a chain handle, or null for unsupported formats (rates 8–384 kHz; 1, 2, 6 or 8 channels). */
@CName("spatia_chain_create")
fun chainCreate(sampleRate: Int, channels: Int): COpaquePointer? = try {
    StableRef.create(ChainHandle(DspChain(sampleRate, channels))).asCPointer()
} catch (e: Throwable) { null }

@CName("spatia_chain_destroy")
fun chainDestroy(h: COpaquePointer?) { h?.asStableRef<ChainHandle>()?.dispose() }

/** 0 = ok, -1 = bad handle/output type, -2 = invalid settings JSON. */
@CName("spatia_chain_set_settings")
fun chainSetSettings(h: COpaquePointer?, json: CPointer<ByteVar>?, output: Int, attenuationDb: Float): Int {
    val c = handle(h) ?: return -1
    val type = outputType(output) ?: return -1
    val s = decodeSettings(readJson(json)) ?: return -2
    return try { c.chain.setSettings(s, type, attenuationDb); 0 } catch (e: Throwable) { -2 }
}

/**
 * Processes [frames] frames: [input] interleaved with the chain's channel count, [output] interleaved stereo.
 * Returns 0, or -1 on bad arguments.
 */
@CName("spatia_chain_process")
fun chainProcess(h: COpaquePointer?, input: CPointer<FloatVar>?, output: CPointer<FloatVar>?, frames: Int): Int {
    val c = handle(h) ?: return -1
    if (input == null || output == null || frames !in 0..MAX_FRAMES) return -1
    if (frames == 0) return 0
    val inCh = c.chain.inChannels
    if (c.inBuf.size < frames * inCh) c.inBuf = FloatArray(frames * inCh)
    if (c.outBuf.size < frames * 2) c.outBuf = FloatArray(frames * 2)
    val inb = c.inBuf; val outb = c.outBuf
    for (i in 0 until frames * inCh) inb[i] = input[i].let { if (it.isFinite()) it else 0f }
    return try {
        c.chain.process(inb, outb, frames)
        c.meters.push(outb, frames, 2)
        for (i in 0 until frames * 2) output[i] = outb[i]
        0
    } catch (e: Throwable) { -1 }
}

@CName("spatia_chain_set_head_yaw")
fun chainSetHeadYaw(h: COpaquePointer?, yawRad: Double) { handle(h)?.chain?.setHeadYaw(yawRad) }

/** Writes [peakDb, rmsDb, limiterReductionDb, normalizerGainDb] into [out] (4 floats). */
@CName("spatia_chain_meters")
fun chainMeters(h: COpaquePointer?, out: CPointer<FloatVar>?): Int {
    val c = handle(h) ?: return -1
    if (out == null) return -1
    out[0] = c.meters.peakDb; out[1] = c.meters.rmsDb
    out[2] = c.chain.limiterReductionDb; out[3] = c.chain.normalizerGainDb
    return 0
}

/** Log-spaced spectrum of recent output into [out] ([bands] floats, dB). */
@CName("spatia_chain_spectrum")
fun chainSpectrum(h: COpaquePointer?, out: CPointer<FloatVar>?, bands: Int): Int {
    val c = handle(h) ?: return -1
    if (out == null || bands !in 1..128) return -1
    val s = c.meters.spectrum(bands)
    for (i in 0 until bands) out[i] = s[i]
    return 0
}

/** Loads a WAV impulse response for the room-IR effect; len 0 clears it. 0 ok, -1 bad handle, -2 invalid WAV. */
@CName("spatia_chain_set_impulse")
fun chainSetImpulse(h: COpaquePointer?, wav: CPointer<ByteVar>?, len: Int): Int {
    val c = handle(h) ?: return -1
    if (len == 0 || wav == null) { c.chain.setConvolver(null); return 0 }
    if (len !in 44..WavReader.MAX_BYTES) return -2
    return try {
        val parsed = WavReader.read(wav.readBytes(len))
        c.chain.setConvolver(Convolver(WavReader.prepareImpulse(parsed, c.chain.sampleRate)))
        0
    } catch (e: Throwable) { -2 }
}

// ---------------- settings logic (so the Windows app shares presets, validation and safety rules) ----------------

/** Validates/clamps settings JSON. Returns complete JSON, or null if unparsable. */
@CName("spatia_validate_settings")
fun validateSettings(json: CPointer<ByteVar>?): CPointer<ByteVar>? =
    decodeSettings(readJson(json))?.let { cString(encodeSettings(it)) }

/** Applies preset [mode] (Mode ordinal) on top of [baseJson] (may be null). */
@CName("spatia_preset")
fun preset(mode: Int, baseJson: CPointer<ByteVar>?): CPointer<ByteVar>? {
    val m = Mode.entries.getOrNull(mode) ?: return null
    val base = if (baseJson == null) SoundSettings() else decodeSettings(readJson(baseJson)) ?: return null
    return cString(encodeSettings(Presets.forMode(m, base)))
}

@CName("spatia_default_for_output")
fun defaultForOutput(output: Int): CPointer<ByteVar>? =
    outputType(output)?.let { cString(encodeSettings(ProfileSelector.defaultFor(it))) }

/** Writes [maxBoostDb, limiterCeilingDb, volumeWarningFraction, isHeadphones(0/1)] into [out]. */
@CName("spatia_output_safety")
fun outputSafety(output: Int, out: CPointer<FloatVar>?): Int {
    val t = outputType(output) ?: return -1
    if (out == null) return -1
    val s = OutputSafety.forType(t)
    out[0] = s.maxBoostDb; out[1] = s.limiterCeilingDb; out[2] = s.volumeWarningFraction
    out[3] = if (s.isHeadphones) 1f else 0f
    return 0
}

/** Same capped per-output boost rule the Android app uses. */
@CName("spatia_cap_boost")
fun capBoost(output: Int, requestedDb: Float): Float =
    outputType(output)?.let { SafetyPolicy.capAppBoostDb(requestedDb, OutputSafety.forType(it)) } ?: 0f

/** [connectedMask]: bit i set = OutputType ordinal i connected; [override] -1 = auto. Returns OutputType ordinal. */
@CName("spatia_active_output")
fun activeOutput(connectedMask: Int, override: Int): Int {
    val connected = OutputType.entries.filter { connectedMask and (1 shl it.ordinal) != 0 }
    return ProfileSelector.activeOutput(connected, outputType(override)).ordinal
}

@Serializable
private class AutoEqResult(
    val ok: Boolean,
    val error: String? = null,
    val preampDb: Float = 0f,
    val filters: List<EqFilter> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Parses an AutoEq / EqualizerAPO text file. Always returns JSON {ok, error | preampDb, filters, warnings}. */
@CName("spatia_parse_autoeq")
fun parseAutoEq(bytes: CPointer<ByteVar>?, len: Int): CPointer<ByteVar> {
    val result = try {
        if (bytes == null || len !in 1..ParametricEqParser.MAX_BYTES) AutoEqResult(false, "File is empty or too large")
        else ParametricEqParser.parse(bytes.readBytes(len)).let { AutoEqResult(true, null, it.preampDb, it.filters, it.warnings) }
    } catch (e: Throwable) {
        AutoEqResult(false, e.message ?: "Could not read the file")
    }
    return cString(SpatiaJson.encodeToString(AutoEqResult.serializer(), result))
}

/** Renders the built-in sample track as interleaved stereo; frame count written to [framesOut]. Free with spatia_free. */
@CName("spatia_sample_track_render")
fun sampleTrackRender(sampleRate: Int, framesOut: CPointer<IntVar>?): CPointer<FloatVar>? = try {
    val t = SampleTrack.render(sampleRate)
    val n = t[0].size
    val p = nativeHeap.allocArray<FloatVar>(n * 2)
    for (i in 0 until n) { p[2 * i] = t[0][i]; p[2 * i + 1] = t[1][i] }
    framesOut?.let { it[0] = n }
    p
} catch (e: Throwable) { null }

@CName("spatia_mode_count")
fun modeCount(): Int = Mode.entries.size
