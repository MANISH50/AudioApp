package io.screenlink.app

import android.app.Application
import android.os.Build
import android.provider.Settings
import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.control.RemoteControl
import io.screenlink.app.data.SettingsStore
import io.screenlink.app.data.PairingStore
import io.screenlink.app.net.LinkClient
import io.screenlink.app.net.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScreenLinkApp : Application() {
    lateinit var pairings: PairingStore
        private set
    lateinit var link: LinkClient
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var control: RemoteControl
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        pairings = PairingStore(this)
        link = LinkClient(pairings, ::deviceName)
        settings = SettingsStore(this)
        control = RemoteControl(scope, link, settings.allowControl)
        // Casting needs the laptop: stop capturing as soon as the connection is gone for good or reconnecting.
        scope.launch {
            link.state.collectLatest { s ->
                if (s !is LinkState.Connected && ScreenCastService.casting.value) ScreenCastService.stop(this@ScreenLinkApp)
            }
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
