// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.discover.DiscoverError
import com.ichi2.anki.discover.LessonStatus
import com.ichi2.anki.discover.LessonSummary
import com.ichi2.anki.discover.OverviewState
import com.ichi2.anki.discover.Source
import com.ichi2.anki.discover.SourceKind
import com.ichi2.anki.discover.SourceStatus

@Composable
internal fun DiscoverOverviewScreen(
    state: OverviewState,
    isConfigured: Boolean,
    contentPadding: PaddingValues,
    onOpenLesson: (LessonSummary) -> Unit,
    onProcess: (Source) -> Unit,
    onAddSource: (title: String, author: String?, kind: SourceKind, note: String?) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showAddSource by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!isConfigured) {
            DiscoverErrorCard(
                text = stringResource(R.string.discover_not_configured),
                actionLabel = stringResource(R.string.open_settings),
                onAction = onOpenSettings,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { error ->
                item {
                    DiscoverErrorCard(
                        text = errorText(error),
                        actionLabel =
                            stringResource(
                                if (error.kind ==
                                    DiscoverError.Kind.Unauthorized
                                ) {
                                    R.string.open_settings
                                } else {
                                    R.string.retry
                                },
                            ),
                        onAction = if (error.kind == DiscoverError.Kind.Unauthorized) onOpenSettings else onRetry,
                    )
                }
            }
            if (state.pendingApprovals > 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            pluralStringResource(R.plurals.discover_pending_approvals, state.pendingApprovals, state.pendingApprovals),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.discover_lessons_header)) }
            if (state.loaded && state.lessons.isEmpty()) {
                item { EmptyText(stringResource(R.string.discover_no_lessons)) }
            }
            items(state.lessons, key = { "lesson-${it.id}" }) { lesson ->
                LessonCard(lesson, onClick = { onOpenLesson(lesson) })
            }
            item { SectionHeader(stringResource(R.string.discover_queue_header)) }
            if (state.loaded && state.queue.isEmpty()) {
                item { EmptyText(stringResource(R.string.discover_queue_empty)) }
            }
            items(state.queue, key = { "source-${it.id}" }) { source ->
                SourceRow(
                    source = source,
                    processing = source.id in state.processingSourceIds,
                    onProcess = { onProcess(source) },
                )
            }
            item {
                OutlinedButton(onClick = { showAddSource = true }, enabled = state.error?.kind != DiscoverError.Kind.Unauthorized) {
                    Icon(painterResource(R.drawable.add_24px), contentDescription = null)
                    Text(stringResource(R.string.discover_add_title), Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onConfirm = { title, author, kind, note ->
                showAddSource = false
                onAddSource(title, author, kind, note)
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LessonCard(
    lesson: LessonSummary,
    onClick: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lesson.subtitle?.let {
                Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(lesson.title, style = MaterialTheme.typography.titleMedium)
            val toReview = lesson.proposalCounts.new
            val details =
                listOfNotNull(
                    if (lesson.status == LessonStatus.READ) stringResource(R.string.discover_lesson_read) else null,
                    if (toReview > 0) cardsToReviewText(toReview) else null,
                )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: Source,
    processing: Boolean,
    onProcess: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.title, style = MaterialTheme.typography.titleSmall)
                source.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    stringResource(source.status.label()),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (source.status == SourceStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (source.status == SourceStatus.FAILED) {
                    source.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (source.canProcess) {
                Button(onClick = onProcess, enabled = !processing) {
                    Text(stringResource(R.string.discover_process_now))
                }
            }
        }
    }
}

private fun SourceStatus.label(): Int =
    when (this) {
        SourceStatus.QUEUED, SourceStatus.UNKNOWN -> R.string.discover_source_status_queued
        SourceStatus.PROCESSING -> R.string.dialog_processing
        SourceStatus.PROCESSED -> R.string.discover_source_status_processed
        SourceStatus.FAILED -> R.string.discover_source_status_failed
    }

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, author: String?, kind: SourceKind, note: String?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(SourceKind.BOOK) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.discover_source_title)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.discover_source_author)) },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(stringResource(option.label())) },
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.discover_source_note)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, author, kind, note) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.discover_add_title))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

private fun SourceKind.label(): Int =
    when (this) {
        SourceKind.BOOK -> R.string.discover_source_kind_book
        SourceKind.LEARNING_PATH -> R.string.discover_source_kind_path
        SourceKind.OTHER -> R.string.discover_source_kind_other
    }

/** Top bar actions of the overview. */
@Composable
internal fun OverviewActions(
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    IconButton(onClick = onRefresh) {
        Icon(painterResource(R.drawable.refresh_24px), contentDescription = stringResource(R.string.discover_refresh))
    }
    IconButton(onClick = onOpenSettings) {
        Icon(painterResource(R.drawable.settings_24px), contentDescription = stringResource(R.string.discover_settings))
    }
}
