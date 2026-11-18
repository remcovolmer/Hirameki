// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ichi2.anki.R
import com.ichi2.anki.brainiac.update.UpdatePhase
import com.ichi2.anki.brainiac.update.UpdateUiState
import com.ichi2.anki.brainiac.update.UpdateViewModel

/**
 * "Update available: 0.2.1 → 0.3.0" with Update and Later, and the progress of the download and
 * installation. Shows nothing while there is no update (or after Later). Showing it runs the
 * automatic check, at most once per 24 hours, so put it on the main screen.
 */
@Composable
fun UpdateBanner(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = updateViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onShown() }
    if (!state.supported) return
    UpdateFlowHost(state, viewModel)
    if (state.bannerVisible) {
        UpdateBannerCard(
            state = state,
            onUpdate = viewModel::requestUpdate,
            onLater = viewModel::later,
            onCancelDownload = viewModel::cancelDownload,
            onDismiss = viewModel::dismissResult,
            modifier = modifier,
        )
    }
}

@Composable
internal fun UpdateBannerCard(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val available = state.available ?: return
    val phase = state.phase
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.update_available_title, state.installedVersion, available.version.toString()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            phaseText(phase)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (phase is UpdatePhase.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                )
            }
            when (phase) {
                is UpdatePhase.Downloading ->
                    LinearProgressIndicator(
                        progress = { phase.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                UpdatePhase.Verifying, is UpdatePhase.Installing, is UpdatePhase.AwaitingConfirmation ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                when (phase) {
                    UpdatePhase.Idle -> {
                        TextButton(onClick = onLater) { Text(stringResource(R.string.button_backup_later)) }
                        Button(onClick = onUpdate) { Text(stringResource(R.string.update_action_update)) }
                    }
                    is UpdatePhase.Failed, UpdatePhase.Canceled -> {
                        TextButton(onClick = onLater) { Text(stringResource(R.string.button_backup_later)) }
                        Button(onClick = onUpdate) { Text(stringResource(R.string.try_again)) }
                    }
                    is UpdatePhase.Downloading ->
                        TextButton(onClick = onCancelDownload) { Text(stringResource(R.string.dialog_cancel)) }
                    // stalled: Android reported nothing after its dialog closed
                    is UpdatePhase.Installing ->
                        if (phase.confirmationShown) {
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_dismiss)) }
                        }
                    UpdatePhase.Succeeded ->
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_action_dismiss)) }
                    // the permission dialog of UpdateFlowHost carries the actions
                    is UpdatePhase.NeedsPermission, UpdatePhase.Verifying, is UpdatePhase.AwaitingConfirmation -> {}
                }
            }
        }
    }
}
