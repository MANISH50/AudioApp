package io.spatia.app.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Counts active media/game playback on the device (AudioManager playback callbacks, no permission needed). Android
 * doesn't reveal which app owns each stream, only that one is playing. That is enough to tell the user "something is
 * playing that Spatia isn't processing".
 */
class PlaybackWatcher(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val _activeMediaStreams = MutableStateFlow(0)
    val activeMediaStreams: StateFlow<Int> = _activeMediaStreams
    private var started = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = update(configs.orEmpty())
    }

    fun start() {
        if (started) return
        started = true
        am.registerAudioPlaybackCallback(callback, Handler(Looper.getMainLooper()))
        update(am.activePlaybackConfigurations)
    }

    private fun update(configs: List<AudioPlaybackConfiguration>) {
        _activeMediaStreams.value = configs.count {
            when (it.audioAttributes.usage) {
                AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN -> true
                else -> false
            }
        }
    }
}
