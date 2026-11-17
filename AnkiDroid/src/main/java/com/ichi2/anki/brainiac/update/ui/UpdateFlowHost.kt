// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ichi2.anki.R
import com.ichi2.anki.brainiac.update.UpdateFailure
import com.ichi2.anki.brainiac.update.UpdatePhase
import com.ichi2.anki.brainiac.update.UpdateUiState
import com.ichi2.anki.brainiac.update.UpdateViewModel
import timber.log.Timber

/** The [UpdateViewModel] of the current ViewModelStoreOwner. */
@Composable
fun updateViewModel(): UpdateViewModel {
    val context = LocalContext.current
    val factory = remember(context) { UpdateViewModel.factory(context) }
    return viewModel(factory = factory)
}

/**
 * The dialogs and Android screens of an update: release notes before the download, the
 * explanation of the install permission and its settings page, and Android's confirmation.
 * Part of [UpdateBanner] and [UpdateSettingsRow]; put one of them on a screen, not both.
 */
@Composable
internal fun UpdateFlowHost(
    state: UpdateUiState,
    viewModel: UpdateViewModel,
) {
    val context = LocalContext.current
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.onPermissionSettingsClosed()
        }
    // the outcome arrives through PackageInstaller's broadcast, not as an activity result
    val confirmLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    val available = state.available
    if (state.confirming && available != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissConfirmation,
            title = { Text(stringResource(R.string.update_confirm_title, available.version.toString())) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.update_confirm_versions, state.installedVersion, available.version.toString()))
                    Text(
                        stringResource(R.string.update_confirm_size, Formatter.formatShortFileSize(context, available.asset.size)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.update_confirm_notes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        available.notes.ifBlank { stringResource(R.string.update_confirm_no_notes) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmUpdate) { Text(stringResource(R.string.update_confirm_install)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissConfirmation) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    val phase = state.phase
    if (phase is UpdatePhase.NeedsPermission && !phase.settingsOpen) {
        AlertDialog(
            onDismissRequest = viewModel::cancelPermission,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.permissionSettingsOpened()
                    try {
                        settingsLauncher.launch(unknownSourcesSettings(context))
                    } catch (e: ActivityNotFoundException) {
                        Timber.w(e, "Update: no settings screen for unknown sources")
                        viewModel.onPermissionSettingsClosed()
                    }
                }) { Text(stringResource(R.string.open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPermission) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }

    LaunchedEffect(phase) {
        if (phase is UpdatePhase.AwaitingConfirmation) {
            val intent = viewModel.takeConfirmationIntent() ?: return@LaunchedEffect
            try {
                confirmLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "Update: no activity for the install confirmation")
                viewModel.dismissResult()
            }
        }
    }
}

private fun unknownSourcesSettings(context: Context) =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

/** One line about the running or finished attempt; null when there is nothing to say. */
@Composable
internal fun phaseText(phase: UpdatePhase): String? =
    when (phase) {
        UpdatePhase.Idle -> null
        is UpdatePhase.Downloading -> stringResource(R.string.update_downloading, phase.percent)
        UpdatePhase.Verifying -> stringResource(R.string.update_verifying)
        is UpdatePhase.NeedsPermission -> stringResource(R.string.update_needs_permission)
        is UpdatePhase.Installing ->
            stringResource(if (phase.confirmationShown) R.string.update_waiting_for_android else R.string.update_installing)
        is UpdatePhase.AwaitingConfirmation -> stringResource(R.string.update_installing)
        UpdatePhase.Succeeded -> stringResource(R.string.update_succeeded)
        UpdatePhase.Canceled -> stringResource(R.string.update_canceled)
        is UpdatePhase.Failed -> stringResource(R.string.update_failed, failureText(phase.failure))
    }

@Composable
internal fun failureText(failure: UpdateFailure): String =
    when (failure) {
        UpdateFailure.Network -> stringResource(R.string.update_failure_network)
        UpdateFailure.RateLimited -> stringResource(R.string.update_failure_rate_limited)
        is UpdateFailure.Http -> stringResource(R.string.update_failure_http, failure.status)
        UpdateFailure.InvalidResponse -> stringResource(R.string.update_failure_invalid_response)
        UpdateFailure.HostNotAllowed -> stringResource(R.string.update_failure_host)
        UpdateFailure.SizeMismatch -> stringResource(R.string.update_failure_size)
        UpdateFailure.DigestMismatch -> stringResource(R.string.update_failure_digest)
        UpdateFailure.NotAnApk -> stringResource(R.string.update_failure_not_apk)
        UpdateFailure.WrongPackage -> stringResource(R.string.update_failure_wrong_package)
        UpdateFailure.NotNewer -> stringResource(R.string.update_failure_not_newer)
        UpdateFailure.WrongVariant -> stringResource(R.string.update_failure_wrong_variant)
        UpdateFailure.WrongSignature -> stringResource(R.string.update_failure_signature)
        UpdateFailure.Storage -> stringResource(R.string.update_failure_storage)
        UpdateFailure.PermissionDenied -> stringResource(R.string.update_failure_permission)
        is UpdateFailure.InstallFailed ->
            stringResource(R.string.update_failure_install, failure.message ?: failure.status.toString())
    }
