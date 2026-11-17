// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.discover.DiscoverError
import com.ichi2.anki.discover.DiscoverMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DiscoverTopBar(
    title: String,
    onNavigateUp: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            IconButton(onClick = onNavigateUp) {
                Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
    )
}

/** An error with an optional action, shown in place of content that could not be loaded. */
@Composable
internal fun DiscoverErrorCard(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
internal fun errorText(error: DiscoverError): String =
    when (error.kind) {
        DiscoverError.Kind.NotConfigured -> stringResource(R.string.discover_not_configured)
        DiscoverError.Kind.Network -> stringResource(R.string.discover_error_network)
        DiscoverError.Kind.Unauthorized -> stringResource(R.string.discover_error_unauthorized)
        DiscoverError.Kind.NotFound -> stringResource(R.string.discover_error_not_found)
        DiscoverError.Kind.Conflict ->
            error.detail?.let { stringResource(R.string.discover_error_conflict, it) }
                ?: stringResource(R.string.discover_error_conflict_generic)
        DiscoverError.Kind.ServiceUnavailable -> stringResource(R.string.discover_error_unavailable)
        DiscoverError.Kind.Server -> stringResource(R.string.discover_error_server, error.detail.orEmpty())
    }

@Composable
internal fun messageText(message: DiscoverMessage): String =
    when (message) {
        is DiscoverMessage.Error -> errorText(message.error)
        is DiscoverMessage.MissingNotetype -> stringResource(R.string.discover_missing_notetype, message.notetype)
        is DiscoverMessage.UnknownFields ->
            stringResource(R.string.discover_unknown_fields, message.notetype, message.fields.joinToString(", "))
        DiscoverMessage.SavedLocally -> stringResource(R.string.discover_saved_locally)
        is DiscoverMessage.AlreadyDecided ->
            message.detail?.let { stringResource(R.string.discover_error_conflict, it) }
                ?: stringResource(R.string.discover_error_conflict_generic)
        DiscoverMessage.CommentRequired -> stringResource(R.string.discover_comment_required)
        DiscoverMessage.SourceAdded -> stringResource(R.string.discover_source_added)
        DiscoverMessage.ProcessingStarted -> stringResource(R.string.discover_processing_started)
        DiscoverMessage.SettingsSaved -> stringResource(R.string.discover_settings_saved)
    }

@Composable
internal fun cardsToReviewText(count: Int): String = pluralStringResource(R.plurals.discover_cards_to_review, count, count)
