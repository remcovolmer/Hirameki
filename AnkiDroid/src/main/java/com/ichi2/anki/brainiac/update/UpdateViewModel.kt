// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.StateFlow

/**
 * The UI side of [UpdateController]. The state lives in the controller (one per process), so a
 * banner and a settings row with their own ViewModels show the same download.
 */
class UpdateViewModel(
    private val controller: UpdateController,
) : ViewModel() {
    val uiState: StateFlow<UpdateUiState> = controller.state

    fun onShown() = controller.onShown()

    fun checkNow() = controller.checkNow()

    fun setAutoCheck(enabled: Boolean) = controller.setAutoCheck(enabled)

    fun later() = controller.later()

    fun requestUpdate() = controller.requestUpdate()

    fun dismissConfirmation() = controller.dismissConfirmation()

    fun confirmUpdate() = controller.confirmUpdate()

    fun cancelDownload() = controller.cancelDownload()

    fun permissionSettingsOpened() = controller.permissionSettingsOpened()

    fun onPermissionSettingsClosed() = controller.onPermissionSettingsClosed()

    fun cancelPermission() = controller.cancelPermission()

    fun takeConfirmationIntent(): Intent? = controller.takeConfirmationIntent()

    fun dismissResult() = controller.dismissResult()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val controller = UpdateController.get(context)
            return viewModelFactory { initializer { UpdateViewModel(controller) } }
        }
    }
}
