// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.activity.compose.BackHandler
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
import androidx.navigation3.runtime.NavKey
import com.ichi2.anki.R
import com.ichi2.anki.discover.DiscoverPage
import com.ichi2.anki.discover.DiscoverViewModel
import com.ichi2.anki.discover.LessonStatus
import kotlinx.serialization.Serializable

/** Navigation key of the Discover screen in the deck picker's Nav3 graph. */
@Serializable
object DiscoverDestination : NavKey

/**
 * Entry point of the Discover screen.
 *
 * @param onNavigateUp leaves Discover
 * @param onSync starts the app's normal sync, as the sync button of the deck picker does
 */
@Composable
fun DiscoverRoute(
    onNavigateUp: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(context) { DiscoverViewModel.factory(context) }
    val viewModel: DiscoverViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) { viewModel.onShown() }

    BackHandler(enabled = state.page != DiscoverPage.Overview) { viewModel.back() }

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

    val lesson = state.lesson
    Scaffold(
        topBar = {
            when (state.page) {
                DiscoverPage.Overview ->
                    DiscoverTopBar(
                        title = stringResource(R.string.discover_title),
                        onNavigateUp = onNavigateUp,
                        actions = { OverviewActions(onRefresh = viewModel::refresh, onOpenSettings = viewModel::openSettings) },
                    )
                DiscoverPage.Settings ->
                    DiscoverTopBar(title = stringResource(R.string.discover_settings), onNavigateUp = { viewModel.back() })
                DiscoverPage.Lesson ->
                    DiscoverTopBar(
                        title = lesson?.title.orEmpty(),
                        onNavigateUp = { viewModel.back() },
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
                DiscoverPage.Review ->
                    DiscoverTopBar(title = lesson?.title.orEmpty(), onNavigateUp = { viewModel.back() })
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state.page) {
            DiscoverPage.Overview ->
                DiscoverOverviewScreen(
                    state = state.overview,
                    isConfigured = state.isConfigured,
                    contentPadding = padding,
                    onOpenLesson = viewModel::openLesson,
                    onProcess = { viewModel.processSource(it.id) },
                    onAddSource = viewModel::addSource,
                    onRetry = viewModel::refresh,
                    onOpenSettings = viewModel::openSettings,
                )
            DiscoverPage.Settings ->
                DiscoverSettingsScreen(
                    serverUrl = state.serverUrl,
                    token = state.token,
                    contentPadding = padding,
                    onSave = viewModel::saveSettings,
                )
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
                        onDone = { viewModel.back() },
                    )
                }
        }
    }
}
