package io.screenlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenlink.app.R
import io.screenlink.app.cast.Quality
import io.screenlink.app.cast.ScreenCastService
import io.screenlink.app.net.LinkState

@Composable
fun CastScreen(vm: MainViewModel, onStartCast: () -> Unit, modifier: Modifier) {
    val link by vm.link.collectAsStateWithLifecycle()
    val casting by vm.casting.collectAsStateWithLifecycle()
    val error by vm.castError.collectAsStateWithLifecycle()
    val quality by vm.quality.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Page(modifier) {
        Section(stringResource(R.string.section_cast)) {
            NotConnectedHint(link)
            Text(if (casting) stringResource(R.string.cast_on) else stringResource(R.string.cast_off))
            if (error) Text(stringResource(R.string.cast_error), color = MaterialTheme.colorScheme.error)
            if (casting) {
                OutlinedButton({ ScreenCastService.stop(ctx) }) { Text(stringResource(R.string.cast_stop)) }
            } else {
                Button({ vm.clearCastError(); onStartCast() }, enabled = link is LinkState.Connected) {
                    Text(stringResource(R.string.cast_start))
                }
            }
        }
        Section(stringResource(R.string.section_quality)) {
            androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
                Quality.entries.forEach { q ->
                    Row(
                        Modifier.fillMaxWidth().selectable(q == quality, enabled = !casting, role = Role.RadioButton) { vm.setQuality(q) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(q == quality, null, enabled = !casting)
                        Text(stringResource(if (q == Quality.STANDARD) R.string.quality_standard else R.string.quality_high))
                    }
                }
            }
        }
        ControlSection(vm, casting)
        Text(stringResource(R.string.cast_help))
    }
}

@Composable
private fun ControlSection(vm: MainViewModel, casting: Boolean) {
    val allow by vm.allowControl.collectAsStateWithLifecycle()
    val service by vm.controlService.collectAsStateWithLifecycle()
    val whole by vm.wholeScreen.collectAsStateWithLifecycle()
    val ready by vm.controlReady.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Section(stringResource(R.string.section_control)) {
        Text(stringResource(R.string.control_disclosure))
        Row(
            Modifier.fillMaxWidth().toggleable(allow, role = Role.Switch) { vm.setAllowControl(it) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.control_switch), Modifier.weight(1f))
            Switch(allow, null)
        }
        Text(stringResource(if (service) R.string.control_service_on else R.string.control_service_off))
        if (!service) {
            OutlinedButton({
                runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(stringResource(R.string.control_open_settings)) }
            Text(stringResource(R.string.control_restricted_hint), style = MaterialTheme.typography.bodySmall)
        }
        if (casting && !whole) Text(stringResource(R.string.control_single_app), color = MaterialTheme.colorScheme.error)
        if (ready) Text(stringResource(R.string.control_ready), color = MaterialTheme.colorScheme.primary)
    }
}
