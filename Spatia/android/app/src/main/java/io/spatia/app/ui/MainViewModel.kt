package io.spatia.app.ui

import android.app.Application
import android.content.Intent
import android.media.AudioTrack
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.spatia.app.R
import io.spatia.app.SpatiaApp
import io.spatia.app.capabilities.Capability
import io.spatia.app.system.SessionEffectService
import io.spatia.app.system.TestSound
import io.spatia.dsp.analysis.HearingProfile
import io.spatia.dsp.io.ParametricEqParser
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpatiaApp
    val config = app.engine.config
    val sessions = app.sessionEffects.sessions
    val volume = app.volume.state

    private val _capabilities = MutableStateFlow<List<Capability>>(emptyList())
    val capabilities: StateFlow<List<Capability>> = _capabilities
    /** One-shot user messages (string resource + optional detail). */
    val message = MutableStateFlow<Pair<Int, String?>?>(null)

    /** Tab the UI should switch to (e.g. after another app opens Spatia as its equaliser). */
    val requestedTab = MutableStateFlow<Int?>(null)
    val activeMediaStreams = app.playbackWatcher.activeMediaStreams

    private val _testPlaying = MutableStateFlow(false)
    val testPlaying: StateFlow<Boolean> = _testPlaying
    private var testTrack: AudioTrack? = null
    private var testJob: Job? = null

    init { refreshDevice() }

    fun refreshDevice() {
        app.outputs.refresh(); app.volume.refresh()
        // Foreground services may only start while we are visible, so (re)start here. Read the stored value, not the
        // flow's initial default, so a user who switched Spatia off isn't switched back on.
        viewModelScope.launch {
            if (app.settings.load().systemWideEnabled) startService()
        }
        viewModelScope.launch { _capabilities.value = withContext(Dispatchers.Default) { app.capabilities.query() } }
    }

    private fun startService() {
        try { SessionEffectService.start(getApplication()) } catch (e: RuntimeException) {
            message.value = R.string.msg_service_failed to null
        }
    }

    // ---------- main switch / test ----------
    fun setEnabled(enable: Boolean) {
        app.settings.update { it.copy(systemWideEnabled = enable, globalMode = it.globalMode || enable) }
        if (enable) startService() else SessionEffectService.stop(getApplication())
    }

    fun setGlobalMode(enable: Boolean) = app.settings.update { it.copy(globalMode = enable) }

    /** Re-creates every effect; used after the user turned off the phone's own sound enhancer. */
    fun retryEffects() = app.sessionEffects.reattachAll()

    /** Plays a few seconds of music through the normal media path, so the user hears what other apps get. */
    fun playTest() {
        stopTest()
        testJob = viewModelScope.launch {
            val track = try {
                withContext(Dispatchers.Default) { TestSound.pcm() } // warm-up keeps the main thread free
                TestSound.play()
            } catch (e: Exception) {
                message.value = R.string.test_failed to null; return@launch
            }
            testTrack = track
            _testPlaying.value = true
            delay((TestSound.SECONDS * 1000).toLong() + 200)
            stopTest()
        }
    }

    fun stopTest() {
        testJob?.cancel(); testJob = null
        testTrack?.let { t -> try { t.stop() } catch (_: IllegalStateException) { }; t.release() }
        testTrack = null
        _testPlaying.value = false
    }

    /** App settings page: where MIUI/ColorOS/OxygenOS/Funtouch keep "Battery: No restrictions" and "Autostart". */
    fun openAppSettings() {
        val ctx = getApplication<Application>()
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (_: RuntimeException) { }
    }

    // ---------- sound ----------
    fun applyMode(mode: Mode) = app.engine.applyMode(mode)
    fun tweak(t: (SoundSettings) -> SoundSettings) = app.engine.tweak(t)
    /** Changes that are not part of a "sound" preset (on/off, correction on/off) keep the current mode. */
    fun edit(t: (SoundSettings) -> SoundSettings) = app.engine.editActive(t)

    fun importCorrection(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { readBounded(uri, ParametricEqParser.MAX_BYTES)?.let { ParametricEqParser.parse(it) } }
        }
        result.onSuccess { r ->
            if (r == null) { message.value = R.string.msg_file_too_large to null; return@onSuccess }
            edit { it.copy(correction = r.filters, correctionPreampDb = r.preampDb, correctionEnabled = true) }
            message.value = R.string.msg_correction_imported to "${r.filters.size}" +
                if (r.warnings.isEmpty()) "" else " (${r.warnings.size})"
        }.onFailure { message.value = R.string.msg_correction_failed to it.message }
    }

    /** Reads at most [max] bytes; returns null if the file is larger (input validation before parsing). */
    private fun readBounded(uri: Uri, max: Int): ByteArray? =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                out.write(chunk, 0, n)
                if (out.size() > max) return@use null
            }
            out.toByteArray()
        }

    fun saveHearingResult(left: List<Float>, right: List<Float>) {
        val comp = HearingProfile.compensation(left, right)
        edit { it.copy(hearingCompensation = comp, hearingEnabled = true) }
    }

    // ---------- output / safety ----------
    fun setManualOutput(t: OutputType?) = app.settings.update { it.copy(manualOutput = t) }
    fun setRememberPerDevice(v: Boolean) = app.settings.update { it.copy(rememberPerDevice = v) }
    fun setDoseTracking(v: Boolean) = app.settings.update { it.copy(doseTrackingEnabled = v) }
    fun acknowledgeVolume() = app.settings.update { it.copy(volumeWarningAcknowledgedAt = System.currentTimeMillis()) }
    fun reduceVolume() = app.volume.reduceToSafe()

    // ---------- per-app ----------
    fun attachFromEffectPanel(req: io.spatia.app.system.EffectPanelRequest) {
        app.settings.update { it.copy(systemWideEnabled = true) }
        startService()
        app.sessionEffects.startObserving()
        app.sessionEffects.open(req.sessionId, req.packageName)
        requestedTab.value = 3
    }

    fun setAppBoost(pkg: String, db: Float) = app.settings.update { it.copy(appBoostDb = it.appBoostDb + (pkg to db)) }

    override fun onCleared() {
        stopTest()
        super.onCleared()
    }
}
