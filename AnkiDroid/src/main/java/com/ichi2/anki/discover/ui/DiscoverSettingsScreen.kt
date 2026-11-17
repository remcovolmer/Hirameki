// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.discover.DiscoverConfig

@Composable
internal fun DiscoverSettingsScreen(
    serverUrl: String,
    token: String,
    contentPadding: PaddingValues,
    onSave: (serverUrl: String, token: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(serverUrl) }
    var appToken by rememberSaveable { mutableStateOf(token) }
    val valid = DiscoverConfig.parse(url, appToken) != null
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.discover_server_url)) },
            placeholder = { Text(stringResource(R.string.discover_server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = appToken,
            onValueChange = { appToken = it },
            label = { Text(stringResource(R.string.discover_app_token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!valid && (url.isNotBlank() || appToken.isNotBlank())) {
            Text(
                stringResource(R.string.discover_settings_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = { onSave(url, appToken) }, enabled = valid) {
            Text(stringResource(R.string.save))
        }
    }
}
