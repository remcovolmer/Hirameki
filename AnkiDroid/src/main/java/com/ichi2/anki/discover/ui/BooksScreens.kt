// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.ichi2.anki.R
import com.ichi2.anki.discover.BookFilter
import com.ichi2.anki.discover.DiscoverConfig
import com.ichi2.anki.discover.DiscoverError
import com.ichi2.anki.discover.OverviewState
import com.ichi2.anki.discover.Source
import com.ichi2.anki.discover.SourceKind
import com.ichi2.anki.discover.SourceStatus
import com.ichi2.anki.discover.bookShelf
import com.ichi2.anki.discover.initials
import com.ichi2.anki.discover.summaryFirstLine

/** The Books tab: books grouped by topic, filtered by status; dismissed ones folded away. */
@Composable
internal fun BooksOverviewScreen(
    state: OverviewState,
    config: DiscoverConfig?,
    filter: BookFilter,
    dismissedExpanded: Boolean,
    contentPadding: PaddingValues,
    onFilter: (BookFilter) -> Unit,
    onToggleDismissed: () -> Unit,
    onOpenBook: (Source) -> Unit,
    onAddSource: (title: String, author: String?, kind: SourceKind, note: String?) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissOrphan: (proposalId: Long) -> Unit,
) {
    var showAddSource by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (config == null) {
            NotConfiguredCard(onOpenSettings)
            return@Column
        }
        val shelf = remember(state.sources, filter) { bookShelf(state.sources, filter) }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            overviewProblems(state, onRetry, onOpenSettings, onDismissOrphan)
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BookFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilter(option) },
                            label = { Text("${stringResource(option.label())} (${shelf.counts[option] ?: 0})") },
                        )
                    }
                }
            }
            if (state.loaded && shelf.groups.isEmpty()) {
                item { EmptyText(stringResource(R.string.discover_books_empty)) }
            }
            shelf.groups.forEach { group ->
                item(key = "topic-${group.topic}") {
                    SectionHeader(group.topic ?: stringResource(R.string.discover_topic_other))
                }
                items(group.books, key = { "book-${it.id}" }) { book -> BookRow(book, config, onClick = { onOpenBook(book) }) }
            }
            item {
                OutlinedButton(onClick = { showAddSource = true }, enabled = state.error?.kind != DiscoverError.Kind.Unauthorized) {
                    Icon(painterResource(R.drawable.add_24px), contentDescription = null)
                    Text(stringResource(R.string.discover_add_title), Modifier.padding(start = 8.dp))
                }
            }
            if (shelf.dismissed.isNotEmpty()) {
                item {
                    TextButton(onClick = onToggleDismissed) {
                        Text(stringResource(R.string.discover_dismissed_section, shelf.dismissed.size))
                    }
                }
                if (dismissedExpanded) {
                    items(shelf.dismissed, key = { "dismissed-${it.id}" }) { book -> BookRow(book, config, onClick = { onOpenBook(book) }) }
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
internal fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private fun BookFilter.label(): Int =
    when (this) {
        BookFilter.ToReview -> R.string.discover_filter_to_review
        BookFilter.Processing -> R.string.discover_filter_in_progress
        BookFilter.Processed -> R.string.discover_source_status_processed
    }

@Composable
private fun BookRow(
    book: Source,
    config: DiscoverConfig,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BookCover(book, config, Modifier.width(56.dp).height(84.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            byline(book)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            book.summaryFirstLine()?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (book.status == SourceStatus.FAILED) {
                Text(
                    stringResource(R.string.discover_source_status_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun byline(book: Source): String? = listOfNotNull(book.author, book.year?.toString()).joinToString(" · ").ifEmpty { null }

/**
 * The cover from the service, loaded with the app token and cached by Coil (memory and disk).
 * Without a cover, or when it fails to load, a placeholder shows the title's initials.
 */
@Composable
internal fun BookCover(
    book: Source,
    config: DiscoverConfig,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    if (!book.hasCover) {
        CoverPlaceholder(book, modifier.clip(shape))
        return
    }
    val context = LocalContext.current
    val url = config.coverUrl(book.id)
    val request =
        remember(url, config.token) {
            ImageRequest
                .Builder(context)
                .data(url)
                .addHeader("Authorization", "Bearer ${config.token}")
                .memoryCacheKey(url)
                .diskCacheKey(url)
                .crossfade(true)
                .build()
        }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = stringResource(R.string.discover_cover_description, book.title),
        contentScale = ContentScale.Crop,
        loading = { CoverPlaceholder(book, Modifier.fillMaxSize()) },
        error = { CoverPlaceholder(book, Modifier.fillMaxSize()) },
        modifier = modifier.clip(shape),
    )
}

@Composable
private fun CoverPlaceholder(
    book: Source,
    modifier: Modifier,
) {
    Box(modifier.background(MaterialTheme.colorScheme.tertiaryContainer).padding(4.dp), contentAlignment = Alignment.Center) {
        Text(
            book.initials(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            textAlign = TextAlign.Center,
        )
    }
}

/** One book: cover, description and what to do with it. */
@Composable
internal fun BookDetailScreen(
    book: Source,
    config: DiscoverConfig?,
    busy: Boolean,
    processing: Boolean,
    contentPadding: PaddingValues,
    onProcess: () -> Unit,
    onDismiss: (comment: String?) -> Unit,
    onRestore: () -> Unit,
    onOpenLessons: () -> Unit,
) {
    var askDismiss by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (config != null) BookCover(book, config, Modifier.width(120.dp).height(180.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                byline(
                    book,
                )?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                book.topic?.let {
                    Text(
                        it.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                StatusChip(book.status)
            }
        }
        BookActions(book, busy, processing, onProcess, onAskDismiss = { askDismiss = true }, onRestore, onOpenLessons)
        book.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = readingStyle()) }
        if (book.keyPoints.isNotEmpty()) {
            Text(stringResource(R.string.discover_book_key_points), style = blockTitleStyle())
            MdText(book.keyPoints.joinToString("\n") { "- $it" })
        }
        book.why?.takeIf { it.isNotBlank() }?.let { why ->
            val colors = MaterialTheme.colorScheme
            Surface(shape = RoundedCornerShape(16.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.discover_book_why).uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(why, style = readingStyle())
                }
            }
        }
        if (book.status == SourceStatus.FAILED) book.note?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (askDismiss) {
        DismissBookDialog(
            onCancel = { askDismiss = false },
            onConfirm = {
                askDismiss = false
                onDismiss(it)
            },
        )
    }
}

@Composable
private fun BookActions(
    book: Source,
    busy: Boolean,
    processing: Boolean,
    onProcess: () -> Unit,
    onAskDismiss: () -> Unit,
    onRestore: () -> Unit,
    onOpenLessons: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (book.canProcess) {
            Button(onClick = onProcess, enabled = !processing && !busy) { Text(stringResource(R.string.discover_process_now)) }
        }
        if (book.canDismiss) {
            OutlinedButton(onClick = onAskDismiss, enabled = !busy) { Text(stringResource(R.string.discover_not_interested)) }
        }
        if (book.canRestore) {
            Button(onClick = onRestore, enabled = !busy) { Text(stringResource(R.string.discover_restore)) }
        }
        if (book.status == SourceStatus.PROCESSED && book.lessonCount > 0) {
            Button(onClick = onOpenLessons) {
                Text(pluralStringResource(R.plurals.discover_open_lessons, book.lessonCount, book.lessonCount))
            }
        }
    }
}

@Composable
private fun StatusChip(status: SourceStatus) {
    val colors = MaterialTheme.colorScheme
    val (label, color) =
        when (status) {
            SourceStatus.QUEUED, SourceStatus.UNKNOWN -> R.string.discover_source_status_queued to colors.onSurfaceVariant
            SourceStatus.PROCESSING -> R.string.dialog_processing to colors.primary
            SourceStatus.PROCESSED -> R.string.discover_source_status_processed to colors.primary
            SourceStatus.FAILED -> R.string.discover_source_status_failed to colors.error
            SourceStatus.DISMISSED -> R.string.discover_source_status_dismissed to colors.onSurfaceVariant
        }
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun DismissBookDialog(
    onCancel: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var comment by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.discover_dismiss_title)) },
        text = {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text(stringResource(R.string.discover_dismiss_comment)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(comment.trim().ifEmpty { null }) }) { Text(stringResource(R.string.discover_not_interested)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.dialog_cancel)) } },
    )
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
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceKind.entries.forEach { option ->
                        FilterChip(selected = kind == option, onClick = { kind = option }, label = { Text(stringResource(option.label())) })
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.discover_source_note)) },
                    modifier = Modifier.widthIn(min = 200.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title, author, kind, note) }, enabled = title.isNotBlank()) {
                Text(stringResource(R.string.discover_add_title))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private fun SourceKind.label(): Int =
    when (this) {
        SourceKind.BOOK -> R.string.discover_source_kind_book
        SourceKind.LEARNING_PATH -> R.string.discover_source_kind_path
        SourceKind.OTHER -> R.string.discover_source_kind_other
    }

/** Top bar actions of both lists. */
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
