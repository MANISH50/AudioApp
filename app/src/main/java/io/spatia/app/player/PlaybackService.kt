package io.spatia.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.spatia.app.SpatiaApp
import io.spatia.dsp.analysis.DoseTracker
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Background playback with a MediaSession; audio goes through [SpatiaAudioProcessor]. */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as SpatiaApp
        val processor = SpatiaAudioProcessor(app.engine)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(processor))
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                app.engine.replayGainDb.value = readReplayGain(tracks) ?: 0f
            }
        })
        session = MediaSession.Builder(this, player).build()
        scope.launch { trackDose(app, player) }
    }

    /** Opt-in weekly dose estimate for headphone listening in the built-in player (local only). */
    private suspend fun trackDose(app: SpatiaApp, player: Player) {
        while (true) {
            kotlinx.coroutines.delay(DOSE_INTERVAL_MS)
            val cfg = app.engine.config.value
            if (!cfg.app.doseTrackingEnabled || !player.isPlaying) continue
            if (!io.spatia.dsp.model.OutputSafety.forType(cfg.output).isHeadphones) continue
            val rms = app.engine.meters?.rmsDb ?: continue
            val spl = DoseTracker.estimateSpl(cfg.volume.fraction, rms)
            val add = DoseTracker.doseFraction(spl, DOSE_INTERVAL_MS / 1000.0)
            val week = java.time.LocalDate.now().toEpochDay() / 7
            app.settings.update { st ->
                val base = if (st.doseWeekStartEpochDay == week) st.doseFraction else 0.0
                st.copy(doseWeekStartEpochDay = week, doseFraction = base + add)
            }
        }
    }

    private fun readReplayGain(tracks: Tracks): Float? {
        val tags = HashMap<String, String>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
            for (i in 0 until group.length) {
                val md = group.getTrackFormat(i).metadata ?: continue
                for (j in 0 until md.length()) when (val e = md[j]) {
                    is VorbisComment -> tags[e.key.uppercase()] = e.value
                    is TextInformationFrame -> if (e.id == "TXXX" && e.description != null) {
                        tags[e.description!!.uppercase()] = e.values.firstOrNull().orEmpty()
                    }
                }
            }
        }
        return ReplayGain.gainDb(tags)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val DOSE_INTERVAL_MS = 10_000L
    }
}
