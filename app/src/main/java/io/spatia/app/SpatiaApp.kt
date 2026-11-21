package io.spatia.app

import android.app.Application
import io.spatia.app.audio.AudioEngine
import io.spatia.app.capabilities.DeviceCapabilities
import io.spatia.app.data.SettingsRepository
import io.spatia.app.output.OutputMonitor
import io.spatia.app.safety.VolumeGuard
import io.spatia.app.spatial.HeadTrackerSource
import io.spatia.app.system.SessionEffectsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency graph: the app is small enough that a DI framework would only add attack surface. */
class SpatiaApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings by lazy { SettingsRepository(this, appScope) }
    val outputs by lazy { OutputMonitor(this) }
    val volume by lazy { VolumeGuard(this, outputs) }
    val headTracker by lazy { HeadTrackerSource(this) }
    val engine by lazy { AudioEngine(appScope, settings, outputs, volume, headTracker) }
    val sessionEffects by lazy { SessionEffectsManager(this, appScope, engine) }
    val capabilities by lazy { DeviceCapabilities(this) }
    val playbackWatcher by lazy { io.spatia.app.system.PlaybackWatcher(this) }

    override fun onCreate() {
        super.onCreate()
        outputs.start()
        volume.start()
        engine.start()
        playbackWatcher.start()
    }
}
