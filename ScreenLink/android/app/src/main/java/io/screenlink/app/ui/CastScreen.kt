package io.screenlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
        Text(stringResource(R.string.cast_help))
    }
}
