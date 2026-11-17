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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.discover.Citation
import com.ichi2.anki.discover.Lesson
import com.ichi2.anki.discover.LessonScreen
import com.ichi2.anki.discover.LessonState
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A lesson, one screen at a time (swipe or "next"). The page after the last screen lists the
 * citations and leads to the cards.
 */
@Composable
internal fun DiscoverLessonScreen(
    state: LessonState,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onFinishReading: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        val lesson = state.lesson
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null ->
                DiscoverErrorCard(
                    text = errorText(state.error),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                    modifier = Modifier.padding(16.dp),
                )
            lesson != null -> LessonPager(lesson, cardsToReview = state.toReview.size, onFinishReading = onFinishReading)
        }
    }
}

@Composable
private fun LessonPager(
    lesson: Lesson,
    cardsToReview: Int,
    onFinishReading: () -> Unit,
) {
    val pageCount = lesson.screens.size + 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            val screen = lesson.screens.getOrNull(page)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (page == 0) {
                    lesson.subtitle?.let {
                        Text(it.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (screen != null) {
                    ScreenContent(screen)
                } else {
                    CitationsContent(lesson.citations)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val page = pagerState.currentPage
            TextButton(
                onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                enabled = page > 0,
            ) { Text(stringResource(R.string.discover_previous)) }
            Text(
                stringResource(R.string.discover_page_indicator, page + 1, pageCount),
                style = MaterialTheme.typography.labelMedium,
            )
            if (page < pageCount - 1) {
                Button(onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } }) {
                    Text(stringResource(R.string.discover_next))
                }
            } else {
                Button(onClick = onFinishReading) {
                    Text(
                        if (cardsToReview > 0) {
                            pluralStringResource(R.plurals.discover_review_cards, cardsToReview, cardsToReview)
                        } else {
                            stringResource(R.string.discover_done_reading)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenContent(screen: LessonScreen) {
    screen.heading?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
    lessonParagraphs(screen.body).forEach { paragraph ->
        Text(boldMarkup(paragraph), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CitationsContent(citations: List<Citation>) {
    val uriHandler = LocalUriHandler.current
    Text(stringResource(R.string.discover_sources_header), style = MaterialTheme.typography.headlineSmall)
    citations.forEach { citation ->
        Column(
            Modifier.fillMaxWidth().clickable {
                try {
                    uriHandler.openUri(citation.url)
                } catch (e: IllegalArgumentException) {
                    Timber.w(e, "Discover: cannot open citation")
                }
            },
        ) {
            Text(
                citation.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
            Text(citation.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            citation.checked?.let {
                Text(stringResource(R.string.discover_source_checked, it), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
