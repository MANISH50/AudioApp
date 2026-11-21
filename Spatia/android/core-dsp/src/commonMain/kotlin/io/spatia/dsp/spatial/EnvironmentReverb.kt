package io.spatia.dsp.spatial

import io.spatia.dsp.ParamRamp
import io.spatia.dsp.filters.Biquad
import io.spatia.dsp.filters.BiquadCoeffs
import io.spatia.dsp.filters.FilterType
import io.spatia.dsp.model.EnvironmentPreset
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Acoustic-space simulator used as a parallel *send* effect: the original signal passes through untouched at unity
 * gain and the room is added on top, so the music itself is never thinned, filtered or delayed.
 *
 * Wet path: band-limited send -> input delay line (pre-delay + early reflection taps) -> 4 allpass diffusers ->
 * 8-line feedback delay network (Householder mixing, per-line RT60 gains, HF damping, slow modulation) plus an
 * optional ping-pong echo -> tone filter -> stereo width -> ramped wet gain.
 */
class EnvironmentReverb(private val sampleRate: Int) {
    private val srScale = sampleRate / 48000.0

    // ---- input line (pre-delay + early reflections), stereo ----
    private val inLen = (sampleRate * 0.32).toInt() + 2
    private val inL = FloatArray(inLen)
    private val inR = FloatArray(inLen)
    private var inPos = 0
    private var preDelay = 0
    private var tapDelay = IntArray(0)
    private var tapGainL = FloatArray(0)
    private var tapGainR = FloatArray(0)

    // ---- diffusers ----
    private val apBase = intArrayOf(142, 107, 379, 277)
    private val ap = Array(4) { FloatArray((apBase[it] * srScale).toInt() + 1) }
    private val apPos = IntArray(4)
    private var apCoef = 0.6f

    // ---- FDN ----
    private val fdnBase = intArrayOf(1123, 1361, 1597, 1847, 2063, 2333, 2557, 2803)
    private val modDepth = 6.0 * srScale
    private val lines = Array(8) { FloatArray((fdnBase[it] * 2.0 * srScale + 2 * modDepth + 4).toInt()) }
    private val linePos = IntArray(8)
    private val lineLen = DoubleArray(8)
    private val fb = FloatArray(8)
    private val lp = FloatArray(8)
    private var damping = 0f
    private var lfoPhase = 0.0
    private val lfoInc = 2 * PI * 0.31 / sampleRate
    private val fdnOut = FloatArray(8)

    // ---- echo (ping-pong) ----
    private val echoMax = (sampleRate * 0.8).toInt() + 2
    private val echoL = FloatArray(echoMax)
    private val echoR = FloatArray(echoMax)
    private var echoPos = 0
    private var echoLenL = 1
    private var echoLenR = 1
    private var echoFb = 0f
    private var echoLpL = 0f
    private var echoLpR = 0f
    private val echoLpCoef = kotlin.math.exp(-2 * PI * 3500.0 / sampleRate).toFloat()

    // ---- tone + gains ----
    private val sendHpL = Biquad(); private val sendHpR = Biquad()
    private val wetLpL = Biquad(); private val wetLpR = Biquad()
    private var preset: EnvironmentPreset? = null
    private var erGain = 0f
    private var tailGain = 0f
    private var echoGain = 0f
    private var width = 1f
    private var rt60 = 1f
    private val wet = ParamRamp(0f, maxOf(1, sampleRate / 20)) // 50 ms

    /** Wet signal of the last [process] call, for tests and metering. */
    var lastWetL = FloatArray(0); private set
    var lastWetR = FloatArray(0); private set

    /** Load a space. Clears the tail; callers fade around this (see DspChain duck-and-swap). */
    fun setPreset(p: EnvironmentPreset?, intensity: Float = EnvironmentPreset.DEFAULT_INTENSITY) {
        preset = p
        reset()
        if (p == null) { wet.snap(0f); return }
        preDelay = ms(p.preDelayMs).coerceIn(0, inLen - 2)
        val norm = 1f / sqrt(p.reflections.size.coerceAtLeast(1).toFloat())
        tapDelay = IntArray(p.reflections.size) { (preDelay + ms(p.reflections[it].timeMs)).coerceIn(0, inLen - 2) }
        tapGainL = FloatArray(p.reflections.size) { p.reflections[it].gainL * norm }
        tapGainR = FloatArray(p.reflections.size) { p.reflections[it].gainR * norm }
        erGain = p.reflectionGain
        tailGain = p.tailGain
        echoGain = p.echoGain
        echoLenL = ms(p.echoMs).coerceIn(1, echoMax - 2)
        echoLenR = (echoLenL * 1.07).toInt().coerceIn(1, echoMax - 2)
        width = p.width.coerceIn(0f, 1.5f)
        apCoef = p.diffusion.coerceIn(0f, 0.8f)
        damping = p.damping.coerceIn(0f, 0.95f)
        val size = p.size.coerceIn(0.3f, 2f)
        for (i in 0 until 8) lineLen[i] = fdnBase[i] * size * srScale
        sendHpL.coeffs = BiquadCoeffs.design(FilterType.HIGH_PASS, p.lowCutHz.toDouble(), 0.707, 0.0, sampleRate.toDouble())
        sendHpR.coeffs = sendHpL.coeffs
        wetLpL.coeffs = BiquadCoeffs.design(FilterType.LOW_PASS, p.highCutHz.toDouble(), 0.707, 0.0, sampleRate.toDouble())
        wetLpR.coeffs = wetLpL.coeffs
        applyIntensity(intensity, snap = true)
    }

    /** Intensity meter 0..1 (0.5 = designed default). Smooth: gains ramp, decay changes are tiny per call. */
    fun setIntensity(intensity: Float) = applyIntensity(intensity, snap = false)

    private var lastIntensity = Float.NaN
    private fun applyIntensity(intensity: Float, snap: Boolean) {
        val p = preset ?: return
        if (!snap && intensity == lastIntensity) return
        lastIntensity = intensity
        val sc = p.scaled(intensity)
        rt60 = sc.rt60
        for (i in 0 until 8) fb[i] = 10.0.pow(-3.0 * (lineLen[i] + modDepth) / (rt60 * sampleRate)).toFloat()
        echoFb = sc.echoFeedback
        if (snap) wet.snap(sc.wetGain) else wet.setTarget(sc.wetGain)
    }

    private fun ms(v: Float) = (v / 1000.0 * sampleRate).toInt()

    /** Adds the room to interleaved stereo [buf] in place. The dry signal is never modified. */
    fun process(buf: FloatArray, frames: Int) {
        if (preset == null) return
        if (lastWetL.size < frames) { lastWetL = FloatArray(frames); lastWetR = FloatArray(frames) }
        val tg = tailGain * 0.5f
        for (f in 0 until frames) {
            val dryL = buf[2 * f]; val dryR = buf[2 * f + 1]
            inL[inPos] = sendHpL.process(dryL)
            inR[inPos] = sendHpR.process(dryR)

            // Early reflections.
            var erL = 0f; var erR = 0f
            for (k in tapDelay.indices) {
                val idx = (inPos - tapDelay[k] + inLen) % inLen
                val m = (inL[idx] + inR[idx]) * 0.5f
                erL += m * tapGainL[k]; erR += m * tapGainR[k]
            }
            val pd = (inPos - preDelay + inLen) % inLen
            val pdL = inL[pd]; val pdR = inR[pd]
            inPos = (inPos + 1) % inLen

            // Diffuse the send, then the late tail.
            var x = (pdL + pdR) * 0.5f
            for (a in 0 until 4) {
                val line = ap[a]; val p = apPos[a]
                val d = line[p]
                val y = -apCoef * x + d
                line[p] = x + apCoef * y
                apPos[a] = (p + 1) % line.size
                x = y
            }
            lfoPhase += lfoInc
            if (lfoPhase > 2 * PI) lfoPhase -= 2 * PI
            var sum = 0f
            for (i in 0 until 8) {
                val mod = modDepth * sin(lfoPhase + i * 0.785)
                val out = readFrac(lines[i], linePos[i], lineLen[i] + modDepth + mod)
                lp[i] = out + (lp[i] - out) * damping // in-loop HF absorption, unity at DC
                fdnOut[i] = lp[i] * fb[i]
                sum += fdnOut[i]
            }
            val h = sum * (2f / 8f)
            var tailL = 0f; var tailR = 0f
            for (i in 0 until 8) {
                val line = lines[i]
                val v = fdnOut[i] - h + x
                line[linePos[i]] = if (v.isFinite()) v else 0f
                linePos[i] = (linePos[i] + 1) % line.size
                if (i and 1 == 0) tailL += if (i and 2 == 0) lp[i] else -lp[i]
                else tailR += if (i and 2 == 0) lp[i] else -lp[i]
            }

            // Ping-pong echo.
            var eL = 0f; var eR = 0f
            if (echoGain > 0f) {
                eL = echoL[(echoPos - echoLenL + echoMax) % echoMax]
                eR = echoR[(echoPos - echoLenR + echoMax) % echoMax]
                echoLpL = eL + (echoLpL - eL) * echoLpCoef
                echoLpR = eR + (echoLpR - eR) * echoLpCoef
                echoL[echoPos] = pdL + echoFb * echoLpR
                echoR[echoPos] = pdR + echoFb * echoLpL
                echoPos = (echoPos + 1) % echoMax
            }

            var wl = wetLpL.process(erGain * erL + tg * tailL + echoGain * eL)
            var wr = wetLpR.process(erGain * erR + tg * tailR + echoGain * eR)
            val mid = (wl + wr) * 0.5f; val side = (wl - wr) * 0.5f * width
            val g = wet.next()
            wl = (mid + side) * g; wr = (mid - side) * g
            lastWetL[f] = wl; lastWetR[f] = wr
            buf[2 * f] = dryL + wl
            buf[2 * f + 1] = dryR + wr
        }
    }

    private fun readFrac(line: FloatArray, writePos: Int, delay: Double): Float {
        val n = line.size
        val d = delay.coerceIn(1.0, (n - 2).toDouble())
        val di = floor(d).toInt()
        val fr = (d - di).toFloat()
        val i0 = (writePos - di + n) % n
        val i1 = (i0 - 1 + n) % n
        return line[i0] * (1 - fr) + line[i1] * fr
    }

    fun reset() {
        inL.fill(0f); inR.fill(0f); ap.forEach { it.fill(0f) }; lines.forEach { it.fill(0f) }
        lp.fill(0f); echoL.fill(0f); echoR.fill(0f); echoLpL = 0f; echoLpR = 0f
        sendHpL.reset(); sendHpR.reset(); wetLpL.reset(); wetLpR.reset()
        lastIntensity = Float.NaN
    }
}
