package io.spatia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.app.capabilities.CapStatus

@Composable
fun StatusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val caps by vm.capabilities.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.status_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.status_output, stringResource(outputLabel(cfg.output))))
                OutlinedButton(onClick = vm::refreshDevice) { Text(stringResource(R.string.status_refresh)) }
            }
        }
        items(caps) { c ->
            val statusText = stringResource(
                when (c.status) {
                    CapStatus.AVAILABLE -> R.string.status_available
                    CapStatus.LIMITED -> R.string.status_limited
                    CapStatus.UNAVAILABLE -> R.string.status_unavailable
                },
            )
            val title = stringResource(c.title, *c.reasonArgs.take(1).toTypedArray())
            val reason = stringResource(c.reason, *c.reasonArgs.toTypedArray())
            ListItem(
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$title. $statusText. $reason" },
                headlineContent = { Text(title) },
                supportingContent = { Text(reason) },
                leadingContent = {
                    Icon(
                        when (c.status) {
                            CapStatus.AVAILABLE -> Icons.Filled.CheckCircle
                            CapStatus.LIMITED -> Icons.Filled.RemoveCircle
                            CapStatus.UNAVAILABLE -> Icons.Filled.Block
                        },
                        contentDescription = null,
                        tint = when (c.status) {
                            CapStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                            CapStatus.LIMITED -> MaterialTheme.colorScheme.tertiary
                            CapStatus.UNAVAILABLE -> MaterialTheme.colorScheme.error
                        },
                    )
                },
                trailingContent = { Row { Text(statusText, style = MaterialTheme.typography.labelMedium) } },
            )
        }
    }
}
