package io.spatia.app.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.spatia.app.SpatiaApp
import kotlinx.coroutines.launch

/** Turns Spatia back on after a restart when the user left it on. Only reacts to the system's boot broadcast. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as SpatiaApp
        val pending = goAsync()
        app.appScope.launch {
            try {
                val raw = app.settings.load()
                if (raw.systemWideEnabled) SessionEffectService.start(context)
            } catch (_: RuntimeException) {
                // Starting a foreground service can be refused on some ROMs; the app starts it when next opened.
            } finally { pending.finish() }
        }
    }

}
