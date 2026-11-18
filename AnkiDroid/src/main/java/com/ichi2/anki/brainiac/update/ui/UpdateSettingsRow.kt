// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ichi2.anki.R
import com.ichi2.anki.brainiac.update.CheckStatus
import com.ichi2.anki.brainiac.update.UpdatePhase
import com.ichi2.anki.brainiac.update.UpdateUiState
import com.ichi2.anki.brainiac.update.UpdateViewModel

/**
 * The installed version, "Check for updates" and the switch for the automatic check. When an
 * update is known (also one hidden with Later) it offers Update and shows the progress.
 */
@Composable
fun UpdateSettingsRow(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = updateViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.supported) UpdateFlowHost(state, viewModel)
    UpdateSettingsContent(
        state = state,
        onCheckNow = viewModel::checkNow,
        onAutoCheckChange = viewModel::setAutoCheck,
        onUpdate = viewModel::requestUpdate,
        modifier = modifier,
    )
}

@Composable
internal fun UpdateSettingsContent(
    state: UpdateUiState,
    onCheckNow: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.update_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.update_settings_version, state.installedVersion), style = MaterialTheme.typography.bodyMedium)
        if (!state.supported) {
            Text(
                stringResource(R.string.update_settings_not_supported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_auto_check), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.update_auto_check_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.autoCheckEnabled, onCheckedChange = onAutoCheckChange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckNow, enabled = state.check != CheckStatus.Checking && !state.busy) {
                Text(stringResource(R.string.update_check_now))
            }
            if (state.available != null && !state.busy) {
                Button(onClick = onUpdate) { Text(stringResource(R.string.update_action_update)) }
            }
        }
        checkText(state.check)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (state.check is CheckStatus.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        val phase = state.phase
        phaseText(phase)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (phase is UpdatePhase.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (phase is UpdatePhase.Downloading) {
            LinearProgressIndicator(progress = { phase.percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun checkText(check: CheckStatus): String? =
    when (check) {
        CheckStatus.Idle -> null
        CheckStatus.Checking -> stringResource(R.string.update_status_checking)
        is CheckStatus.UpToDate -> stringResource(R.string.update_status_up_to_date, check.version)
        is CheckStatus.Available -> stringResource(R.string.update_status_available, check.version.toString())
        is CheckStatus.NoMatchingAsset -> stringResource(R.string.update_status_no_asset, check.version.toString(), check.variant)
        is CheckStatus.Failed -> stringResource(R.string.update_status_failed, failureText(check.failure))
        CheckStatus.UnknownInstall -> stringResource(R.string.update_status_unknown_install)
    }
