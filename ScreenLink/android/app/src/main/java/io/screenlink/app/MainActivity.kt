package io.screenlink.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.ui.MainViewModel
import io.screenlink.app.ui.ScreenLinkRoot
import io.screenlink.app.ui.ScreenLinkTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** The system's own "Start recording or casting?" consent screen. Asked fresh for every cast session. */
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCastService.start(this, result.resultCode, data, vm.quality.value)
        }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Casting works either way; the permission only makes the "ScreenLink is casting" notification visible.
        askConsent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScreenLinkTheme { ScreenLinkRoot(vm, onStartCast = ::startCast) } }
    }

    private fun startCast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askConsent()
        }
    }

    private fun askConsent() {
        if (!vm.link.value.let { it is io.screenlink.app.net.LinkState.Connected }) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        consent.launch(mpm.createScreenCaptureIntent())
    }
}
