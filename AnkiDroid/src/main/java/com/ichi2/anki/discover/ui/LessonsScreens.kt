// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.brainiac.update.ui.UpdateBanner
import com.ichi2.anki.discover.AfterLesson
import com.ichi2.anki.discover.DiscoverConfig
import com.ichi2.anki.discover.DiscoverError
import com.ichi2.anki.discover.LessonProgress
import com.ichi2.anki.discover.LessonStack
import com.ichi2.anki.discover.LessonSummary
import com.ichi2.anki.discover.OrphanReason
import com.ichi2.anki.discover.OverviewState
import com.ichi2.anki.discover.Source
import com.ichi2.anki.discover.StackKind
import com.ichi2.anki.discover.progress

/** The Lessons tab: what is ready to read, one card per stack. */
@Composable
internal fun LessonsOverviewScreen(
    state: OverviewState,
    config: DiscoverConfig?,
    contentPadding: PaddingValues,
    onOpenStack: (LessonStack) -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissOrphan: (proposalId: Long) -> Unit,
) {
    var showFinished by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        UpdateBanner(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        if (config == null) {
            NotConfiguredCard(onOpenSettings)
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            overviewProblems(state, onRetry, onOpenSettings, onDismissOrphan)
            val open = state.openStacks
            if (state.loaded && open.isEmpty()) {
                item { EmptyText(stringResource(R.string.discover_no_lessons)) }
            }
            items(open, key = { "stack-${it.key}" }) { stack ->
                StackCard(stack, state.source(stack.sourceId), config, onClick = { onOpenStack(stack) })
            }
            val finished = state.finishedStacks
            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }) {
                        Text("${stringResource(R.string.discover_finished_stacks)} (${finished.size})")
                    }
                }
                if (showFinished) {
                    items(finished, key = { "finished-${it.key}" }) { stack ->
                        StackCard(stack, state.source(stack.sourceId), config, onClick = { onOpenStack(stack) })
                    }
                }
            }
        }
    }
}

@Composable
internal fun NotConfiguredCard(onOpenSettings: () -> Unit) {
    DiscoverErrorCard(
        text = stringResource(R.string.discover_not_configured),
        actionLabel = stringResource(R.string.open_settings),
        onAction = onOpenSettings,
        modifier = Modifier.padding(16.dp),
    )
}

/** Load errors, approvals waiting to be reported and refused approvals, above either list. */
internal fun androidx.compose.foundation.lazy.LazyListScope.overviewProblems(
    state: OverviewState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissOrphan: (proposalId: Long) -> Unit,
) {
    state.error?.let { error ->
        item {
            val unauthorized = error.kind == DiscoverError.Kind.Unauthorized
            DiscoverErrorCard(
                text = errorText(error),
                actionLabel = stringResource(if (unauthorized) R.string.open_settings else R.string.retry),
                onAction = if (unauthorized) onOpenSettings else onRetry,
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
    items(state.orphans, key = { "orphan-${it.proposalId}" }) { orphan ->
        DiscoverErrorCard(
            text = orphanText(orphan.noteId ?: 0, orphan.orphanReason ?: OrphanReason.PROPOSAL_NOT_NEW),
            actionLabel = stringResource(R.string.discover_orphan_dismiss),
            onAction = { onDismissOrphan(orphan.proposalId) },
        )
    }
}

@Composable
internal fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StackCard(
    stack: LessonStack,
    source: Source?,
    config: DiscoverConfig,
    onClick: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (stack.kind == StackKind.BOOK && source != null) {
                BookCover(source, config, Modifier.width(56.dp).height(84.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // the loose lessons' title says it all
                if (stack.kind != StackKind.LOOSE) {
                    Text(
                        stringResource(stack.kind.label()).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stack.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                StackProgress(stack)
                val details =
                    listOfNotNull(
                        stack.remainingMinutes?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.discover_minutes_left, it, it) },
                        stack.cardsToReview.takeIf { it > 0 }?.let { cardsToReviewText(it) },
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
}

@Composable
private fun StackProgress(stack: LessonStack) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(R.plurals.discover_stack_progress, stack.total, stack.doneCount, stack.total),
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(
            progress = { if (stack.total == 0) 0f else stack.doneCount.toFloat() / stack.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun StackKind.label(): Int =
    when (this) {
        StackKind.BOOK -> R.string.discover_source_kind_book
        StackKind.PATH -> R.string.discover_source_kind_path
        StackKind.LOOSE -> R.string.discover_stack_kind_loose
    }

/** An opened stack: its lessons in order, and "Continue" to the first one that is not done. */
@Composable
internal fun StackScreen(
    stack: LessonStack,
    source: Source?,
    config: DiscoverConfig?,
    contentPadding: PaddingValues,
    onContinue: () -> Unit,
    onOpenLesson: (LessonSummary) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.kind == StackKind.BOOK && source != null && config != null) {
                    BookCover(source, config, Modifier.width(72.dp).height(108.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StackProgress(stack)
                    stack.remainingMinutes?.takeIf { it > 0 }?.let {
                        Text(
                            pluralStringResource(R.plurals.discover_minutes_left, it, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        stack.nextLesson?.let { next ->
            item {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (stack.doneCount == 0) {
                            stringResource(R.string.discover_start_stack)
                        } else {
                            "${stringResource(R.string.dialog_continue)}: ${stack.numberOf(next.id)}. ${next.title}"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column {
                    stack.lessons.forEachIndexed { index, lesson ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        StackLessonRow(index + 1, lesson, isNext = lesson.id == stack.nextLesson?.id, onClick = { onOpenLesson(lesson) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StackLessonRow(
    number: Int,
    lesson: LessonSummary,
    isNext: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = lesson.progress
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (badgeColor, textColor) =
            when {
                progress == LessonProgress.Done -> colors.secondaryContainer to colors.onSecondaryContainer
                isNext -> colors.primary to colors.onPrimary
                else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
            }
        Surface(shape = CircleShape, color = badgeColor, modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (progress ==
                        LessonProgress.Done
                    ) {
                        "✓"
                    } else {
                        "$number"
                    },
                    color = textColor,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                lesson.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                color = if (progress == LessonProgress.Done) colors.onSurfaceVariant else colors.onSurface,
            )
            val status =
                when (progress) {
                    LessonProgress.Open -> stringResource(R.string.discover_lesson_not_read)
                    LessonProgress.CardsLeft -> cardsToReviewText(lesson.proposalCounts.new)
                    LessonProgress.Done -> stringResource(R.string.discover_lesson_done)
                }
            val minutes =
                lesson.readingMinutes?.takeIf { progress == LessonProgress.Open }?.let {
                    pluralStringResource(R.plurals.discover_reading_minutes, it, it)
                }
            Text(
                listOfNotNull(status, minutes).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** After a lesson: on to the next one of the stack, or the end of the stack. */
@Composable
internal fun AfterLessonContent(
    after: AfterLesson?,
    approvedInLesson: Int,
    onNextLesson: () -> Unit,
    onBack: () -> Unit,
    canGoToStack: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (after) {
            is AfterLesson.Next -> {
                Text(stringResource(R.string.discover_lesson_finished), style = MaterialTheme.typography.headlineSmall)
                if (approvedInLesson > 0) {
                    Text(pluralStringResource(R.plurals.discover_cards_added, approvedInLesson, approvedInLesson))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.discover_up_next).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(after.lesson.title, style = MaterialTheme.typography.titleMedium)
                }
                Button(onClick = onNextLesson) {
                    Text(stringResource(R.string.discover_next_lesson, after.number, after.total))
                }
                OutlinedButton(onClick = onBack) {
                    Text(stringResource(if (canGoToStack) R.string.discover_back_to_stack else R.string.discover_back_to_lessons))
                }
            }
            is AfterLesson.StackFinished, null -> {
                Text(stringResource(R.string.discover_stack_finished), style = MaterialTheme.typography.headlineSmall)
                after?.let {
                    Text(it.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (it.approvedInStack > 0) {
                        Text(pluralStringResource(R.plurals.discover_cards_added, it.approvedInStack, it.approvedInStack))
                        Text(stringResource(R.string.discover_syncing_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = onBack) { Text(stringResource(R.string.discover_back_to_lessons)) }
            }
        }
    }
}
