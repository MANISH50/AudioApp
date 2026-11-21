package io.spatia.app

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.spatia.app.system.EffectPanelRequest
import io.spatia.app.ui.MainViewModel
import io.spatia.app.ui.SpatiaRoot
import io.spatia.app.ui.SpatiaTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SpatiaTheme { SpatiaRoot(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Another app's "Equaliser" button opened us with its audio session. */
    private fun handleIntent(intent: Intent?) {
        val req = EffectPanelRequest.parse(
            intent?.action,
            intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE) ?: AudioEffect.ERROR_BAD_VALUE,
            intent?.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME),
        ) ?: return
        vm.attachFromEffectPanel(req)
        setResult(RESULT_OK)
    }

    override fun onResume() {
        super.onResume()
        vm.refreshDevice()
    }
}
