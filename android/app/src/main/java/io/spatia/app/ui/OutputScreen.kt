package io.spatia.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.dsp.model.OutputSafety
import io.spatia.dsp.model.OutputType

@Composable
fun OutputScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val volume by vm.volume.collectAsStateWithLifecycle()
    val safety = OutputSafety.forType(cfg.output)

    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(stringResource(R.string.output_active)) {
            Text(stringResource(outputLabel(cfg.output)), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (cfg.app.manualOutput == null) R.string.output_auto else R.string.output_manual),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.output_safety, safety.maxBoostDb, safety.limiterCeilingDb,
                (safety.volumeWarningFraction * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.output_volume, (volume.fraction * 100).toInt(), volume.attenuationDb),
                style = MaterialTheme.typography.bodySmall)
        }

        Section(stringResource(R.string.output_override)) {
            Column(Modifier.selectableGroup()) {
                OverrideRow(stringResource(R.string.output_auto_detect), cfg.app.manualOutput == null) { vm.setManualOutput(null) }
                OutputType.entries.forEach { t ->
                    OverrideRow(stringResource(outputLabel(t)), cfg.app.manualOutput == t) { vm.setManualOutput(t) }
                }
            }
            SwitchRow(stringResource(R.string.output_remember), cfg.app.rememberPerDevice,
                supporting = stringResource(R.string.output_remember_help), onChange = vm::setRememberPerDevice)
        }

        Section(stringResource(R.string.dose_title)) {
            SwitchRow(stringResource(R.string.dose_enable), cfg.app.doseTrackingEnabled,
                supporting = stringResource(R.string.dose_help), onChange = vm::setDoseTracking)
            if (cfg.app.doseTrackingEnabled) {
                Text(stringResource(R.string.dose_value, (cfg.app.doseFraction * 100).toInt()), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun OverrideRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
