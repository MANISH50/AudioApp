package io.screenlink.app.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.core.content.IntentCompat
import io.screenlink.app.MainActivity
import io.screenlink.app.R
import io.screenlink.app.ScreenLinkApp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground service that owns the screen capture. Follows the Android 14+ rules: it goes foreground with type
 * mediaProjection *before* using the user's consent token, the token is used once, and capture ends when the user
 * stops it (notification, status-bar chip, lock screen on some devices) or the laptop connection ends.
 */
class ScreenCastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var caster: ScreenCaster? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCasting()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val quality = Quality.entries.getOrElse(intent?.getIntExtra(EXTRA_QUALITY, 0) ?: 0) { Quality.STANDARD }
        if (resultCode != Activity.RESULT_OK || data == null || caster != null) {
            if (caster == null) stopSelf()
            return START_NOT_STICKY
        }

        // 1) Foreground first (required before getMediaProjection on Android 14+).
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: RuntimeException) {
            Log.e(TAG, "startForeground failed", e)
            fail(); return START_NOT_STICKY
        }

        // 2) Use the consent token.
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try { mpm.getMediaProjection(resultCode, data) } catch (e: SecurityException) { Log.e(TAG, "projection", e); null }
        if (proj == null) { fail(); return START_NOT_STICKY }
        projection = proj

        // 3) Register the stop callback before creating the virtual display (required on Android 14+).
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopCasting() } }
            override fun onCapturedContentResize(width: Int, height: Int) {
                // Android 14+ can share a single app instead of the whole screen; taps could then land in the wrong
                // place, so laptop control is only allowed while the whole screen is shared.
                val (sw, sh) = screenSize()
                _wholeScreen.value = sameSize(width, height, sw, sh)
                caster?.onScreenChanged()
            }
        }, main)

        val app = application as ScreenLinkApp
        val c = ScreenCaster(proj, app.link, quality, ::screenSize, resources.displayMetrics.densityDpi) {
            main.post { fail() }
        }
        caster = c
        app.link.onKeyframeRequest = { c.requestKeyframe() }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) { if (displayId == Display.DEFAULT_DISPLAY) c.onScreenChanged() }
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(listener, main)
        displayListener = listener
        c.start()
        // Keep the notification honest about whether the laptop can also control the phone.
        scope.launch {
            app.control.ready.collect { ready ->
                if (_casting.value) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(ready))
            }
        }
        app.link.sendCastState(true)
        _casting.value = true
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun fail() {
        _lastError.value = true
        stopCasting()
    }

    private fun stopCasting() {
        val app = application as ScreenLinkApp
        app.link.onKeyframeRequest = null
        displayListener?.let { getSystemService(DisplayManager::class.java).unregisterDisplayListener(it) }
        displayListener = null
        caster?.release()
        caster = null
        projection?.stop()
        projection = null
        if (_casting.value) app.link.sendCastState(false)
        _casting.value = false
        _wholeScreen.value = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (caster != null) stopCasting()
        super.onDestroy()
    }

    private fun notification(controlled: Boolean = false): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(if (controlled) R.string.notif_text_control else R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.cast_stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "ScreenLinkCast"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.screenlink.app.STOP_CAST"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_QUALITY = "quality"

        private val _casting = MutableStateFlow(false)
        val casting: StateFlow<Boolean> = _casting.asStateFlow()
        private val _wholeScreen = MutableStateFlow(true)
        /** False while Android 14+ is sharing a single app rather than the entire screen. */
        val wholeScreen: StateFlow<Boolean> = _wholeScreen.asStateFlow()

        /** Same size in either orientation, within 3%. */
        fun sameSize(w: Int, h: Int, sw: Int, sh: Int): Boolean {
            fun close(a: Int, b: Int) = b > 0 && kotlin.math.abs(a - b) <= b * 0.03
            return (close(w, sw) && close(h, sh)) || (close(w, sh) && close(h, sw))
        }

        private val _lastError = MutableStateFlow(false)
        val lastError: StateFlow<Boolean> = _lastError.asStateFlow()
        fun clearError() { _lastError.value = false }

        fun start(context: Context, resultCode: Int, data: Intent, quality: Quality) {
            _lastError.value = false
            val i = Intent(context, ScreenCastService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_QUALITY, quality.ordinal)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            if (!_casting.value) return
            context.startService(Intent(context, ScreenCastService::class.java).setAction(ACTION_STOP))
        }
    }
}
