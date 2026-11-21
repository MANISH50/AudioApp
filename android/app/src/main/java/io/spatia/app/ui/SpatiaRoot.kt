package io.spatia.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.app.safety.VolumeGuard

private data class Tab(val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(R.string.tab_player, Icons.Filled.PlayCircle),
    Tab(R.string.tab_sound, Icons.Filled.Equalizer),
    Tab(R.string.tab_output, Icons.Filled.Headphones),
    Tab(R.string.tab_apps, Icons.Filled.Apps),
    Tab(R.string.tab_status, Icons.Filled.Info),
)

@Composable
fun SpatiaRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    val msg by vm.message.collectAsStateWithLifecycle()
    val requested by vm.requestedTab.collectAsStateWithLifecycle()
    LaunchedEffect(requested) { requested?.let { tab = it; vm.requestedTab.value = null } }
    LaunchedEffect(msg) {
        msg?.let { (res, detail) ->
            snackbar.showSnackbar(ctx.getString(res) + (detail?.let { " $it" } ?: ""))
            vm.message.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> PlayerScreen(vm, m)
            1 -> SoundScreen(vm, m)
            2 -> OutputScreen(vm, m)
            3 -> AppsScreen(vm, m)
            else -> StatusScreen(vm, m)
        }
    }
    VolumeWarning(vm)
}

@Composable
private fun VolumeWarning(vm: MainViewModel) {
    val volume by vm.volume.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val acknowledgedRecently = System.currentTimeMillis() - cfg.app.volumeWarningAcknowledgedAt < VolumeGuard.REACKNOWLEDGE_MS
    if (!volume.aboveSafeLevel || acknowledgedRecently) return
    val headphones = io.spatia.dsp.model.OutputSafety.forType(cfg.output).isHeadphones
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.volume_warning_title)) },
        text = { Text(stringResource(if (headphones) R.string.volume_warning_headphones else R.string.volume_warning_speaker)) },
        confirmButton = { TextButton(onClick = vm::reduceVolume) { Text(stringResource(R.string.volume_reduce)) } },
        dismissButton = { TextButton(onClick = vm::acknowledgeVolume) { Text(stringResource(R.string.volume_continue)) } },
    )
}
