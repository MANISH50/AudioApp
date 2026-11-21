package io.spatia.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.spatia.app.R
import io.spatia.app.system.SessionEffectKind
import io.spatia.dsp.model.OutputSafety

@Composable
fun AppsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val cfg by vm.config.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val cap = OutputSafety.forType(cfg.output).maxBoostDb

    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        Section(stringResource(R.string.system_title)) {
            Text(stringResource(R.string.system_explain), style = MaterialTheme.typography.bodySmall)
            SwitchRow(stringResource(R.string.global_enable), cfg.app.globalMode, enabled = cfg.app.systemWideEnabled,
                supporting = stringResource(R.string.global_help), onChange = vm::setGlobalMode)
        }

        Section(stringResource(R.string.system_sessions)) {
            if (sessions.isEmpty()) Text(stringResource(R.string.system_no_sessions), style = MaterialTheme.typography.bodySmall)
            sessions.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider()
                val label = if (s.isGlobal) stringResource(R.string.session_global) else appLabel(ctx, s.packageName)
                Text(label, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        when (s.kind) {
                            SessionEffectKind.DYNAMICS_PROCESSING -> R.string.session_dp
                            SessionEffectKind.EQUALIZER_ONLY -> R.string.session_eq
                            SessionEffectKind.FAILED -> R.string.session_failed
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (s.environmentActive) Text(stringResource(R.string.session_environment_on), style = MaterialTheme.typography.bodySmall)
                if (s.kind == SessionEffectKind.DYNAMICS_PROCESSING && !s.isGlobal) {
                    LabeledSlider(stringResource(R.string.session_boost, label), cfg.app.appBoostDb[s.packageName] ?: 0f,
                        0f..cap, ::dbText) { v -> vm.setAppBoost(s.packageName, v) }
                }
            }
            Text(stringResource(R.string.session_boost_cap, cap), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun appLabel(ctx: android.content.Context, pkg: String): String = try {
    // Package visibility (Android 11+) may hide other apps; fall back to the package name.
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { pkg }
