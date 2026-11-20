package io.spatia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Bathtub
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Stadium
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.spatia.app.R
import io.spatia.dsp.model.Environment
import io.spatia.dsp.model.EnvironmentPreset
import kotlin.math.abs
import kotlin.math.roundToInt

private class EnvUi(val name: Int, val description: Int, val icon: ImageVector)

private val envUi = mapOf(
    Environment.OFF to EnvUi(R.string.env_off, R.string.env_off_desc, Icons.Filled.Close),
    Environment.HALL to EnvUi(R.string.env_hall, R.string.env_hall_desc, Icons.Filled.AccountBalance),
    Environment.CINEMA to EnvUi(R.string.env_cinema, R.string.env_cinema_desc, Icons.Filled.Movie),
    Environment.PARK to EnvUi(R.string.env_park, R.string.env_park_desc, Icons.Filled.Park),
    Environment.BATHROOM to EnvUi(R.string.env_bathroom, R.string.env_bathroom_desc, Icons.Filled.Bathtub),
    Environment.ECHOING to EnvUi(R.string.env_echoing, R.string.env_echoing_desc, Icons.Filled.Landscape),
    Environment.CATHEDRAL to EnvUi(R.string.env_cathedral, R.string.env_cathedral_desc, Icons.Filled.Church),
    Environment.STADIUM to EnvUi(R.string.env_stadium, R.string.env_stadium_desc, Icons.Filled.Stadium),
    Environment.CLUB to EnvUi(R.string.env_club, R.string.env_club_desc, Icons.Filled.Nightlife),
    Environment.LIVING_ROOM to EnvUi(R.string.env_living_room, R.string.env_living_room_desc, Icons.Filled.Weekend),
    Environment.TUNNEL to EnvUi(R.string.env_tunnel, R.string.env_tunnel_desc, Icons.Filled.Subway),
)

/**
 * Acoustic environment picker plus intensity meter. The meter's centre is the designed default for each space;
 * left makes it subtler (fully left = no effect), right makes it stronger.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnvironmentSection(environment: Environment, intensity: Float, onEnvironment: (Environment) -> Unit, onIntensity: (Float) -> Unit) {
    Section(stringResource(R.string.env_title)) {
        Text(stringResource(R.string.env_help), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Environment.entries.forEach { e ->
                val ui = envUi.getValue(e)
                FilterChip(
                    selected = environment == e,
                    onClick = { onEnvironment(e) },
                    label = { Text(stringResource(ui.name)) },
                    leadingIcon = { Icon(ui.icon, contentDescription = null, Modifier.size(FilterChipDefaults.IconSize)) },
                )
            }
        }
        Text(stringResource(envUi.getValue(environment).description), style = MaterialTheme.typography.bodyMedium)

        val off = environment == Environment.OFF
        val subtle = stringResource(R.string.env_subtle)
        val default = stringResource(R.string.env_default)
        val intense = stringResource(R.string.env_intense)
        LabeledSlider(
            label = stringResource(R.string.env_intensity),
            value = intensity,
            range = 0f..1f,
            steps = 19, // 5 % steps; the centre (default) is always reachable
            enabled = !off,
            valueText = { v -> intensityText(v, subtle, default, intense) },
            onCommit = onIntensity,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(subtle, style = MaterialTheme.typography.labelSmall)
            Text(default, style = MaterialTheme.typography.labelSmall)
            Text(intense, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
            onClick = { onIntensity(EnvironmentPreset.DEFAULT_INTENSITY) },
            enabled = !off && abs(intensity - EnvironmentPreset.DEFAULT_INTENSITY) > 0.01f,
        ) { Text(stringResource(R.string.env_reset)) }
        Text(stringResource(R.string.env_player_only), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Default", "Subtle 40%", "Intense 75%": distance from the default, in percent of the half-range. */
fun intensityText(v: Float, subtle: String, default: String, intense: String): String {
    val d = v - EnvironmentPreset.DEFAULT_INTENSITY
    if (abs(d) < 0.01f) return default
    val pct = (abs(d) / 0.5f * 100).roundToInt()
    return if (d < 0) "$subtle −$pct%" else "$intense +$pct%"
}
