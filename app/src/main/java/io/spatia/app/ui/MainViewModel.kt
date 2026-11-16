package io.spatia.app.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.spatia.app.R
import io.spatia.app.SpatiaApp
import io.spatia.app.capabilities.Capability
import io.spatia.app.player.BitPerfect
import io.spatia.app.player.FormatSupport
import io.spatia.app.player.PlaybackService
import io.spatia.app.system.SessionEffectService
import io.spatia.dsp.analysis.HearingProfile
import io.spatia.dsp.io.ParametricEqParser
import io.spatia.dsp.io.WavReader
import io.spatia.dsp.model.Mode
import io.spatia.dsp.model.OutputType
import io.spatia.dsp.model.SoundSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUi(
    val title: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    @StringRes val error: Int? = null,
)

data class MeterUi(val peakDb: Float = -120f, val rmsDb: Float = -120f, val spectrum: FloatArray = FloatArray(0),
                   val limiterDb: Float = 0f)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SpatiaApp
    val config = app.engine.config
    val sessions = app.sessionEffects.sessions
    val volume = app.volume.state
    val headTrackerAvailable = app.headTracker.available

    private val _capabilities = MutableStateFlow<List<Capability>>(emptyList())
    val capabilities: StateFlow<List<Capability>> = _capabilities
    private val _player = MutableStateFlow(PlayerUi())
    val player: StateFlow<PlayerUi> = _player
    private val _meters = MutableStateFlow(MeterUi())
    val meters: StateFlow<MeterUi> = _meters
    /** One-shot user messages (string resource + optional detail). */
    val message = MutableStateFlow<Pair<Int, String?>?>(null)
    private val _sleepRemainingS = MutableStateFlow(0)
    val sleepRemainingS: StateFlow<Int> = _sleepRemainingS
    private val _sleepMinutes = MutableStateFlow(0)
    val sleepMinutes: StateFlow<Int> = _sleepMinutes

    private var controller: MediaController? = null
    private var sleepJob: Job? = null

    init {
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        future.addListener({
            controller = runCatching { future.get() }.getOrNull()?.also { c ->
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = syncPlayer()
                    override fun onPlayerError(error: PlaybackException) {
                        _player.value = _player.value.copy(error = errorMessage(error))
                    }
                })
                syncPlayer()
            }
        }, application.mainExecutor)
        viewModelScope.launch { tick() }
        refreshDevice()
    }

    private suspend fun tick() {
        while (viewModelScope.isActive) {
            syncPlayer()
            app.engine.meters?.let { m ->
                _meters.value = MeterUi(m.peakDb, m.rmsDb, if (_player.value.isPlaying) m.spectrum() else FloatArray(0))
            }
            delay(100)
        }
    }

    private fun syncPlayer() {
        val c = controller ?: return
        _player.value = _player.value.copy(
            title = c.mediaMetadata.title?.toString() ?: _player.value.title,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
        )
    }

    private fun errorMessage(e: PlaybackException): Int = when (e.errorCode) {
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> R.string.error_decoder
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> R.string.error_container
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> R.string.error_file_access
        else -> R.string.error_generic
    }

    fun refreshDevice() {
        app.outputs.refresh(); app.volume.refresh()
        // Foreground services may only start while we are visible, so (re)start here.
        if (config.value.app.systemWideEnabled) SessionEffectService.start(getApplication())
        viewModelScope.launch { _capabilities.value = withContext(Dispatchers.Default) { app.capabilities.query() } }
    }

    // ---------- player ----------
    fun openFile(uri: Uri) {
        val ctx = getApplication<Application>()
        val mime = ctx.contentResolver.getType(uri)
        val name = displayName(uri)
        when (val v = FormatSupport.classify(mime, name, FormatSupport.platformIamfDecoder())) {
            is FormatSupport.Verdict.Unsupported -> { _player.value = _player.value.copy(error = v.reason); return }
            FormatSupport.Verdict.Supported -> Unit
        }
        val item = MediaItem.Builder().setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name ?: ctx.getString(R.string.player_untitled)).build())
            .build()
        _player.value = PlayerUi(title = name)
        controller?.run { setMediaItem(item); prepare(); play() }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.take(200) else null }
    }.getOrNull()

    fun playPause() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { controller?.seekTo(ms) }
    fun clearError() { _player.value = _player.value.copy(error = null) }

    /** Sleep timer: counts down, then fades the player out over 15 s and pauses. */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleepRemainingS.value = 0
        _sleepMinutes.value = minutes
        if (minutes <= 0) { controller?.volume = 1f; return }
        sleepJob = viewModelScope.launch {
            var remaining = minutes * 60
            while (remaining > 0) { _sleepRemainingS.value = remaining; delay(1000); remaining-- }
            val c = controller
            if (c != null) {
                for (i in 30 downTo 0) { c.volume = i / 30f; delay(500) }
                c.pause(); c.volume = 1f
            }
            _sleepRemainingS.value = 0
            _sleepMinutes.value = 0
        }
    }

    // ---------- sound ----------
    fun applyMode(mode: Mode) = app.engine.applyMode(mode)
    fun tweak(t: (SoundSettings) -> SoundSettings) = app.engine.tweak(t)
    /** Changes that are not part of a "sound" preset (A/B, correction on/off) keep the current mode. */
    fun edit(t: (SoundSettings) -> SoundSettings) = app.engine.editActive(t)
    fun recenterHead() = app.engine.recenterHead()

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

    fun importImpulse(uri: Uri) = viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching { readBounded(uri, WavReader.MAX_BYTES)?.let { WavReader.read(it) } }
        }
        result.onSuccess { w ->
            if (w == null) { message.value = R.string.msg_file_too_large to null; return@onSuccess }
            app.engine.setImpulseResponse(w)
            edit { it.copy(convolutionEnabled = true) }
            message.value = R.string.msg_ir_imported to null
        }.onFailure { message.value = R.string.msg_ir_failed to it.message }
    }

    fun clearImpulse() { app.engine.setImpulseResponse(null); edit { it.copy(convolutionEnabled = false) } }
    val hasImpulse: Boolean get() = app.engine.hasImpulse

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

    fun setBitPerfect(enable: Boolean) {
        val usb = app.outputs.state.value.connected.firstOrNull { it.type == OutputType.USB }
        val ok = BitPerfect.apply(getApplication(), usb?.let { app.outputs.deviceInfo(it.id) }, enable)
        if (enable && !ok) { message.value = R.string.msg_bit_perfect_failed to null; return }
        app.settings.update { it.copy(bitPerfectUsb = enable) }
        message.value = R.string.msg_restart_playback to null
    }

    // ---------- system-wide ----------
    fun setSystemWide(enable: Boolean) {
        app.settings.update { it.copy(systemWideEnabled = enable) }
        val ctx = getApplication<Application>()
        if (enable) SessionEffectService.start(ctx) else SessionEffectService.stop(ctx)
    }

    fun setAppBoost(pkg: String, db: Float) = app.settings.update { it.copy(appBoostDb = it.appBoostDb + (pkg to db)) }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
