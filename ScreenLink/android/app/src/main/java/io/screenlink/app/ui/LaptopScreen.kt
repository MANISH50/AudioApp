package io.screenlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenlink.app.R
import io.screenlink.app.net.LinkState

@Composable
fun LaptopScreen(vm: MainViewModel, modifier: Modifier) {
    val link by vm.link.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (link !is LinkState.Connected) vm.scan() }

    Page(modifier) {
        Section(stringResource(R.string.section_status)) {
            Text(statusText(link))
            val busy = link is LinkState.Connected || link is LinkState.Connecting || link is LinkState.WaitingForLaptop
            if (busy) OutlinedButton(vm::disconnect) { Text(stringResource(R.string.disconnect)) }
        }

        Section(stringResource(R.string.section_found)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(vm::scan, enabled = !scanning) { Text(stringResource(R.string.scan)) }
                if (scanning) CircularProgressIndicator(Modifier.size(24.dp))
            }
            if (!scanning && found.isEmpty()) Text(stringResource(R.string.none_found))
            found.forEach { d ->
                val isPaired = paired.any { it.fingerprintHex == d.fingerprintHex }
                ListItem(
                    headlineContent = { Text(d.name) },
                    supportingContent = { Text(if (isPaired) stringResource(R.string.paired_at, d.host) else d.host) },
                    trailingContent = { TextButton({ vm.connect(d) }) { Text(stringResource(R.string.connect)) } },
                )
            }
        }

        if (paired.isNotEmpty()) {
            Section(stringResource(R.string.section_paired)) {
                paired.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text(p.host) },
                        trailingContent = {
                            Row {
                                TextButton({ vm.connect(p) }) { Text(stringResource(R.string.connect)) }
                                TextButton({ vm.forget(p) }) { Text(stringResource(R.string.forget)) }
                            }
                        },
                    )
                }
            }
        }

        Section(stringResource(R.string.section_manual)) {
            var address by rememberSaveable { mutableStateOf("") }
            var invalid by rememberSaveable { mutableStateOf(false) }
            Text(stringResource(R.string.manual_help))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(64); invalid = false },
                label = { Text(stringResource(R.string.manual_label)) },
                singleLine = true,
                isError = invalid,
                supportingText = { if (invalid) Text(stringResource(R.string.manual_invalid)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Button({ invalid = !vm.connectManual(address) }, enabled = address.isNotBlank()) { Text(stringResource(R.string.connect)) }
        }

        Text(stringResource(R.string.laptop_help))
    }
}
