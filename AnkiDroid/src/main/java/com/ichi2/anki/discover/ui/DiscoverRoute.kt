// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ichi2.anki.R
import com.ichi2.anki.discover.AfterLesson
import com.ichi2.anki.discover.DiscoverPage
import com.ichi2.anki.discover.DiscoverTab
import com.ichi2.anki.discover.DiscoverUiState
import com.ichi2.anki.discover.DiscoverViewModel
import com.ichi2.anki.discover.LessonStatus

/**
 * Entry point of the Lessons and Books tabs. Both share one view model (scoped to the activity),
 * so the lists, a book's stack and the settings are the same on both tabs.
 *
 * @param onSelectTab switches the bottom bar (or rail) destination
 * @param onSync starts the app's normal sync, as the sync button of the deck picker does
 */
@Composable
fun DiscoverRoute(
    tab: DiscoverTab,
    onSelectTab: (BrainiacTab) -> Unit,
    onSync: () -> Unit,
    withBottomBar: Boolean = false,
) {
    val context = LocalContext.current
    val factory = remember(context) { DiscoverViewModel.factory(context) }
    val viewModel: DiscoverViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val page = state.page(tab)

    LaunchedEffect(viewModel, tab) { viewModel.onShown(tab) }

    BackHandler(enabled = page != DiscoverPage.Overview) { viewModel.back(tab) }

    LaunchedEffect(state.switchToTab) {
        val target = state.switchToTab ?: return@LaunchedEffect
        viewModel.tabSwitched()
        if (target != tab) onSelectTab(target.brainiacTab)
    }

    LaunchedEffect(state.syncRequested) {
        if (state.syncRequested) {
            viewModel.syncStarted()
            onSync()
        }
    }

    val message = state.message
    val messageText = message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = { DiscoverRouteTopBar(tab, state, viewModel, withBottomBar, onSelectTab) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // only on the lists: a stack, lesson, review or book is read without the bar
        bottomBar = {
            if (withBottomBar && page == DiscoverPage.Overview) {
                BrainiacNavigationBar(tab.brainiacTab, onSelect = onSelectTab)
            }
        },
    ) { padding ->
        when (tab) {
            DiscoverTab.Lessons -> LessonsTabContent(state, viewModel, padding)
            DiscoverTab.Books -> BooksTabContent(state, viewModel, padding)
        }
    }
}

@Composable
private fun DiscoverRouteTopBar(
    tab: DiscoverTab,
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    withBottomBar: Boolean,
    onSelectTab: (BrainiacTab) -> Unit,
) {
    val lesson = state.lesson
    val back: () -> Unit = { viewModel.back(tab) }
    when (state.page(tab)) {
        DiscoverPage.Overview ->
            DiscoverTopBar(
                title = stringResource(tab.brainiacTab.label),
                // with the bottom bar, the other tabs are a tap away: no up arrow
                onNavigateUp = if (withBottomBar) null else ({ onSelectTab(BrainiacTab.Decks) }),
                actions = { OverviewActions(onRefresh = viewModel::refresh, onOpenSettings = { viewModel.openSettings(tab) }) },
            )
        DiscoverPage.Settings -> DiscoverTopBar(title = stringResource(R.string.discover_settings), onNavigateUp = back)
        DiscoverPage.Stack -> DiscoverTopBar(title = state.openStack?.title.orEmpty(), onNavigateUp = back)
        DiscoverPage.Lesson ->
            DiscoverTopBar(
                title = lesson?.title.orEmpty(),
                onNavigateUp = back,
                actions = {
                    // a lesson read before can go straight to its cards
                    val toReview = lesson?.toReview?.size ?: 0
                    if (lesson?.lesson?.status == LessonStatus.READ && toReview > 0) {
                        TextButton(onClick = viewModel::startReview) {
                            Text(pluralStringResource(R.plurals.discover_review_cards, toReview, toReview))
                        }
                    }
                },
            )
        DiscoverPage.Review -> DiscoverTopBar(title = lesson?.title.orEmpty(), onNavigateUp = back)
        DiscoverPage.BookDetail -> DiscoverTopBar(title = state.book?.title.orEmpty(), onNavigateUp = back)
    }
}

@Composable
private fun LessonsTabContent(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    padding: PaddingValues,
) {
    val lesson = state.lesson
    when (state.lessonsPage) {
        DiscoverPage.Overview, DiscoverPage.BookDetail ->
            LessonsOverviewScreen(
                state = state.overview,
                config = state.config,
                contentPadding = padding,
                onOpenStack = { viewModel.openStack(it.key) },
                onRetry = viewModel::refresh,
                onOpenSettings = { viewModel.openSettings(DiscoverTab.Lessons) },
                onDismissOrphan = viewModel::dismissOrphan,
            )
        DiscoverPage.Settings -> SettingsContent(state, viewModel, DiscoverTab.Lessons, padding)
        DiscoverPage.Stack ->
            state.openStack?.let { stack ->
                StackScreen(
                    stack = stack,
                    source = state.overview.source(stack.sourceId),
                    config = state.config,
                    contentPadding = padding,
                    onContinue = viewModel::continueStack,
                    onOpenLesson = viewModel::openLesson,
                )
            }
        DiscoverPage.Lesson ->
            lesson?.let {
                DiscoverLessonScreen(
                    state = it,
                    contentPadding = padding,
                    onRetry = viewModel::retryLesson,
                    onFinishReading = viewModel::finishReading,
                )
            }
        DiscoverPage.Review ->
            lesson?.let {
                DiscoverReviewScreen(
                    state = it,
                    contentPadding = padding,
                    onApprove = viewModel::approve,
                    onFeedback = viewModel::sendFeedback,
                    onReject = viewModel::reject,
                    finished = {
                        val after = state.afterLesson
                        AfterLessonContent(
                            after = after,
                            approvedInLesson = it.approvedCount,
                            onNextLesson = viewModel::openNextLesson,
                            onBack = {
                                if (after is AfterLesson.StackFinished) {
                                    viewModel.closeStack()
                                } else {
                                    viewModel.back(
                                        DiscoverTab.Lessons,
                                    )
                                }
                            },
                            canGoToStack = (state.openStack?.total ?: 0) > 1,
                        )
                    },
                )
            }
    }
}

@Composable
private fun BooksTabContent(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    padding: PaddingValues,
) {
    when (state.booksPage) {
        DiscoverPage.Settings -> SettingsContent(state, viewModel, DiscoverTab.Books, padding)
        DiscoverPage.BookDetail ->
            state.book?.let { book ->
                BookDetailScreen(
                    book = book,
                    config = state.config,
                    busy = book.id in state.overview.busySourceIds,
                    processing = book.id in state.overview.processingSourceIds,
                    contentPadding = padding,
                    onProcess = { viewModel.processSource(book.id) },
                    onDismiss = { viewModel.dismissBook(book.id, it) },
                    onRestore = { viewModel.restoreBook(book.id) },
                    onOpenLessons = { viewModel.openBookLessons(book.id) },
                )
            }
        else ->
            BooksOverviewScreen(
                state = state.overview,
                config = state.config,
                filter = state.bookFilter,
                kindFilter = state.kindFilter,
                dismissedExpanded = state.dismissedExpanded,
                contentPadding = padding,
                onFilter = viewModel::setBookFilter,
                onKindFilter = viewModel::setKindFilter,
                onToggleDismissed = viewModel::toggleDismissed,
                onOpenBook = { viewModel.openBook(it.id) },
                onAddSource = viewModel::addSource,
                onRetry = viewModel::refresh,
                onOpenSettings = { viewModel.openSettings(DiscoverTab.Books) },
                onDismissOrphan = viewModel::dismissOrphan,
            )
    }
}

@Composable
private fun SettingsContent(
    state: DiscoverUiState,
    viewModel: DiscoverViewModel,
    tab: DiscoverTab,
    padding: PaddingValues,
) {
    DiscoverSettingsScreen(
        serverUrl = state.serverUrl,
        token = state.token,
        contentPadding = padding,
        onSave = { url, token -> viewModel.saveSettings(tab, url, token) },
    )
}
