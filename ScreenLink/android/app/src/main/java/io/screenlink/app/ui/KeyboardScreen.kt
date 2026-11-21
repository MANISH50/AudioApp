package io.screenlink.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenlink.app.R
import io.screenlink.app.net.Keys
import io.screenlink.app.net.LinkState
import io.screenlink.app.net.Protocol

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(vm: MainViewModel, modifier: Modifier) {
    val link by vm.link.collectAsStateWithLifecycle()
    val mods by vm.modifiers.collectAsStateWithLifecycle()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val connected = link is LinkState.Connected

    Page(modifier) {
        NotConnectedHint(link)
        Text(stringResource(R.string.keyboard_help))
        OutlinedTextField(
            value = field,
            onValueChange = { new ->
                // Only typing at the end is mirrored (the laptop's cursor can't see ours), so the cursor is pinned there.
                val text = new.text
                vm.typed(field.text, text)
                field = if (text.length > 400 || text.endsWith("\n")) TextFieldValue("")
                else TextFieldValue(text, TextRange(text.length), new.composition)
            },
            enabled = connected,
            label = { Text(stringResource(R.string.keyboard_field)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
        )

        Section(stringResource(R.string.section_modifiers)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ctrl" to Protocol.MOD_CTRL, "Alt" to Protocol.MOD_ALT, "Shift" to Protocol.MOD_SHIFT, "Win" to Protocol.MOD_WIN).forEach { (label, m) ->
                    FilterChip(mods and m != 0, { vm.toggleModifier(m) }, { Text(label) }, enabled = connected)
                }
            }
            Text(stringResource(R.string.modifiers_help))
        }

        Section(stringResource(R.string.section_keys)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("Esc", Keys.ESCAPE, vm, connected)
                KeyButton("Tab", Keys.TAB, vm, connected)
                KeyButton("Enter", Keys.ENTER, vm, connected)
                KeyButton("⌫", Keys.BACKSPACE, vm, connected, stringResource(R.string.key_backspace))
                KeyButton("Del", Keys.DELETE, vm, connected)
                KeyButton("←", Keys.LEFT, vm, connected, stringResource(R.string.key_left))
                KeyButton("↑", Keys.UP, vm, connected, stringResource(R.string.key_up))
                KeyButton("↓", Keys.DOWN, vm, connected, stringResource(R.string.key_down))
                KeyButton("→", Keys.RIGHT, vm, connected, stringResource(R.string.key_right))
                KeyButton("Home", Keys.HOME, vm, connected)
                KeyButton("End", Keys.END, vm, connected)
                KeyButton("PgUp", Keys.PAGE_UP, vm, connected)
                KeyButton("PgDn", Keys.PAGE_DOWN, vm, connected)
                KeyButton("Space", Keys.SPACE, vm, connected)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 12) KeyButton("F${i + 1}", Keys.F1 + i, vm, connected)
            }
        }

        Section(stringResource(R.string.section_shortcuts)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Shortcut(stringResource(R.string.sc_copy), 'C'.code, Protocol.MOD_CTRL, vm, connected)
                Shortcut(stringResource(R.string.sc_paste), 'V'.code, Protocol.MOD_CTRL, vm, connected)
                Shortcut(stringResource(R.string.sc_cut), 'X'.code, Protocol.MOD_CTRL, vm, connected)
                Shortcut(stringResource(R.string.sc_undo), 'Z'.code, Protocol.MOD_CTRL, vm, connected)
                Shortcut(stringResource(R.string.sc_select_all), 'A'.code, Protocol.MOD_CTRL, vm, connected)
                Shortcut(stringResource(R.string.sc_switch), Keys.TAB, Protocol.MOD_ALT, vm, connected)
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, vk: Int, vm: MainViewModel, enabled: Boolean, description: String? = null) {
    FilledTonalButton(
        onClick = { vm.key(vk) },
        enabled = enabled,
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier,
    ) { Text(label) }
}

@Composable
private fun Shortcut(label: String, vk: Int, mods: Int, vm: MainViewModel, enabled: Boolean) {
    AssistChip(onClick = { vm.shortcut(vk, mods) }, label = { Text(label) }, enabled = enabled)
}
