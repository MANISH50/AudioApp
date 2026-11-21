package io.screenlink.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.screenlink.app.R
import io.screenlink.app.net.LinkFailure
import io.screenlink.app.net.LinkState

@Composable
fun ScreenLinkRoot(vm: MainViewModel, onStartCast: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val link by vm.link.collectAsStateWithLifecycle()
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Laptop, null) }, label = { Text(stringResource(R.string.tab_laptop)) })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Cast, null) }, label = { Text(stringResource(R.string.tab_cast)) })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Keyboard, null) }, label = { Text(stringResource(R.string.tab_keyboard)) })
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding).fillMaxSize()
        when (tab) {
            0 -> LaptopScreen(vm, m)
            1 -> CastScreen(vm, onStartCast, m)
            else -> KeyboardScreen(vm, m)
        }
    }

    val s = link
    if (s is LinkState.ConfirmCode) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pair_title, s.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.pair_body))
                    Text(
                        s.code.chunked(3).joinToString(" "),
                        fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = { TextButton({ vm.confirmCode(true) }) { Text(stringResource(R.string.pair_match)) } },
            dismissButton = { TextButton({ vm.confirmCode(false) }) { Text(stringResource(R.string.pair_no_match)) } },
        )
    }
}

/** The page frame: scrollable column with standard padding. */
@Composable
fun Page(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun statusText(s: LinkState): String = when (s) {
    LinkState.Idle -> stringResource(R.string.status_idle)
    is LinkState.Connecting -> stringResource(R.string.status_connecting, s.name)
    is LinkState.ConfirmCode -> stringResource(R.string.status_confirm)
    is LinkState.WaitingForLaptop -> stringResource(R.string.status_waiting, s.name, s.code)
    is LinkState.Connected -> stringResource(R.string.status_connected, s.name)
    is LinkState.Failed -> when (s.reason) {
        LinkFailure.UNREACHABLE -> stringResource(R.string.fail_unreachable)
        LinkFailure.IDENTITY_CHANGED -> stringResource(R.string.fail_identity)
        LinkFailure.REJECTED -> stringResource(R.string.fail_rejected)
        LinkFailure.PAIRING_TIMEOUT -> stringResource(R.string.fail_timeout)
        LinkFailure.LOST -> stringResource(R.string.fail_lost)
        LinkFailure.INCOMPATIBLE -> stringResource(R.string.fail_incompatible)
        LinkFailure.LAPTOP_CLOSED -> stringResource(R.string.fail_closed)
    }
}

@Composable
fun NotConnectedHint(link: LinkState) {
    if (link !is LinkState.Connected) {
        Text(stringResource(R.string.need_connection), color = MaterialTheme.colorScheme.error)
    }
}
