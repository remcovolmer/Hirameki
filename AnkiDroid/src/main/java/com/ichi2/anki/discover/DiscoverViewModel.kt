// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiscoverPage { Overview, Settings, Lesson, Review }

/** An API failure, reduced to what the UI shows. */
data class DiscoverError(
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind { NotConfigured, Network, Unauthorized, NotFound, Conflict, ServiceUnavailable, Server }

    companion object {
        fun from(e: DiscoverApiException) =
            when (e) {
                is DiscoverApiException.Network -> DiscoverError(Kind.Network)
                is DiscoverApiException.Unauthorized -> DiscoverError(Kind.Unauthorized)
                is DiscoverApiException.NotFound -> DiscoverError(Kind.NotFound, e.detail)
                is DiscoverApiException.Conflict -> DiscoverError(Kind.Conflict, e.detail)
                is DiscoverApiException.ServiceUnavailable -> DiscoverError(Kind.ServiceUnavailable, e.detail)
                is DiscoverApiException.InvalidRequest -> DiscoverError(Kind.Server, e.detail)
                is DiscoverApiException.Http -> DiscoverError(Kind.Server, e.detail ?: "HTTP ${e.code}")
                is DiscoverApiException.InvalidResponse -> DiscoverError(Kind.Server, e.message)
            }
    }
}

/** One-off messages, shown as a snackbar and then cleared via [DiscoverViewModel.messageShown]. */
sealed interface DiscoverMessage {
    data class Error(
        val error: DiscoverError,
    ) : DiscoverMessage

    data class MissingNotetype(
        val notetype: String,
    ) : DiscoverMessage

    data class UnknownFields(
        val notetype: String,
        val fields: List<String>,
    ) : DiscoverMessage

    /** The note is in the collection, the service does not know yet. */
    data object SavedLocally : DiscoverMessage

    data class AlreadyDecided(
        val detail: String?,
    ) : DiscoverMessage

    data object CommentRequired : DiscoverMessage

    data object SourceAdded : DiscoverMessage

    data object ProcessingStarted : DiscoverMessage

    data object SettingsSaved : DiscoverMessage
}

data class OverviewState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val lessons: List<LessonSummary> = emptyList(),
    val sources: List<Source> = emptyList(),
    val error: DiscoverError? = null,
    /** Sources whose "process" call is in flight; their button is disabled against double taps. */
    val processingSourceIds: Set<Long> = emptySet(),
    val pendingApprovals: Int = 0,
) {
    /** The queue shows everything the routine has not finished yet. */
    val queue: List<Source> get() = sources.filter { it.status != SourceStatus.PROCESSED }
}

data class LessonState(
    val lessonId: Long,
    val title: String,
    val loading: Boolean = true,
    val lesson: Lesson? = null,
    val error: DiscoverError? = null,
    /** The proposals to review (status `nieuw` when the lesson was opened), in order. */
    val toReview: List<Proposal> = emptyList(),
    val reviewIndex: Int = 0,
    val busy: Boolean = false,
    /** Proposals whose note exists locally but whose "yes" has not reached the service. */
    val savedLocally: Set<Long> = emptySet(),
    val approvedCount: Int = 0,
) {
    val currentProposal: Proposal? get() = toReview.getOrNull(reviewIndex)
    val reviewFinished: Boolean get() = lesson != null && reviewIndex >= toReview.size
}

data class DiscoverUiState(
    val page: DiscoverPage = DiscoverPage.Overview,
    val serverUrl: String = "",
    val token: String = "",
    val overview: OverviewState = OverviewState(),
    val lesson: LessonState? = null,
    val message: DiscoverMessage? = null,
    /** Set when a lesson with approved cards is finished; the route starts the app's sync. */
    val syncRequested: Boolean = false,
) {
    val isConfigured: Boolean get() = DiscoverConfig.parse(serverUrl, token) != null
}

class DiscoverViewModel(
    private val settings: DiscoverSettings,
    private val apiFactory: (DiscoverConfig) -> DiscoverApi,
    private val noteCreator: DiscoverNoteCreator,
    private val pendingApprovals: PendingApprovalStore,
) : ViewModel() {
    private val _uiState =
        MutableStateFlow(
            DiscoverUiState(
                serverUrl = settings.serverUrl,
                token = settings.token,
                overview = OverviewState(pendingApprovals = pendingApprovals.all().size),
            ),
        )
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private fun currentConfig(): DiscoverConfig? = DiscoverConfig.parse(settings.serverUrl, settings.token)

    private fun decisions(api: DiscoverApi) = ProposalDecisions(api, noteCreator, pendingApprovals)

    // ---- overview ----

    /** Called when the Discover screen is shown; reloads the overview unless a lesson is open. */
    fun onShown() {
        if (_uiState.value.page == DiscoverPage.Overview) refresh()
    }

    fun refresh() {
        val api = currentConfig()?.let(apiFactory)
        if (api == null) {
            _uiState.update { it.copy(overview = it.overview.copy(error = DiscoverError(DiscoverError.Kind.NotConfigured))) }
            return
        }
        _uiState.update { it.copy(overview = it.overview.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val stillPending = decisions(api).reportPendingApprovals()
            try {
                val lessons = api.openLessons()
                val sources = api.sources()
                _uiState.update {
                    it.copy(
                        overview =
                            it.overview.copy(
                                loading = false,
                                loaded = true,
                                lessons = lessons,
                                sources = sources,
                                error = null,
                                pendingApprovals = stillPending,
                            ),
                    )
                }
            } catch (e: DiscoverApiException) {
                _uiState.update {
                    it.copy(overview = it.overview.copy(loading = false, error = DiscoverError.from(e), pendingApprovals = stillPending))
                }
            }
        }
    }

    fun addSource(
        title: String,
        author: String?,
        kind: SourceKind,
        note: String?,
    ) {
        val api = currentConfig()?.let(apiFactory) ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            try {
                api.addSource(
                    NewSourceRequest(
                        title = title.trim(),
                        author = author?.trim()?.takeIf { it.isNotEmpty() },
                        kind = kind,
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                    ),
                )
                showMessage(DiscoverMessage.SourceAdded)
                refresh()
            } catch (e: DiscoverApiException) {
                showMessage(DiscoverMessage.Error(DiscoverError.from(e)))
            }
        }
    }

    fun processSource(sourceId: Long) {
        val api = currentConfig()?.let(apiFactory) ?: return
        if (sourceId in _uiState.value.overview.processingSourceIds) return
        _uiState.update { it.copy(overview = it.overview.copy(processingSourceIds = it.overview.processingSourceIds + sourceId)) }
        viewModelScope.launch {
            try {
                api.process(sourceId)
                showMessage(DiscoverMessage.ProcessingStarted)
                refresh()
            } catch (e: DiscoverApiException) {
                showMessage(DiscoverMessage.Error(DiscoverError.from(e)))
            } finally {
                _uiState.update { it.copy(overview = it.overview.copy(processingSourceIds = it.overview.processingSourceIds - sourceId)) }
            }
        }
    }

    // ---- settings ----

    fun openSettings() = _uiState.update { it.copy(page = DiscoverPage.Settings) }

    fun saveSettings(
        serverUrl: String,
        token: String,
    ) {
        settings.save(serverUrl, token)
        _uiState.update {
            it.copy(
                page = DiscoverPage.Overview,
                serverUrl = settings.serverUrl,
                token = settings.token,
                overview = OverviewState(),
                message = DiscoverMessage.SettingsSaved,
            )
        }
        refresh()
    }

    // ---- lesson ----

    fun openLesson(summary: LessonSummary) {
        _uiState.update { it.copy(page = DiscoverPage.Lesson, lesson = LessonState(lessonId = summary.id, title = summary.title)) }
        loadLesson(summary.id)
    }

    fun retryLesson() {
        val lessonId = _uiState.value.lesson?.lessonId ?: return
        updateLesson(lessonId) { it.copy(loading = true, error = null) }
        loadLesson(lessonId)
    }

    private fun loadLesson(lessonId: Long) {
        val api = currentConfig()?.let(apiFactory) ?: return
        viewModelScope.launch {
            try {
                val lesson = api.lesson(lessonId)
                val toReview = lesson.proposals.filter { it.status == ProposalStatus.NEW }
                val savedLocally = toReview.mapNotNull { p -> pendingApprovals.get(p.id)?.let { p.id } }.toSet()
                updateLesson(lessonId) {
                    it.copy(loading = false, lesson = lesson, toReview = toReview, savedLocally = savedLocally)
                }
            } catch (e: DiscoverApiException) {
                updateLesson(lessonId) { it.copy(loading = false, error = DiscoverError.from(e)) }
            }
        }
    }

    /** Marks the lesson as read and moves on to its cards (or back to the overview if it has none). */
    fun finishReading() {
        val lessonState = _uiState.value.lesson ?: return
        val lesson = lessonState.lesson ?: return
        val api = currentConfig()?.let(apiFactory) ?: return
        _uiState.update {
            if (lessonState.toReview.isEmpty()) {
                it.copy(page = DiscoverPage.Overview, lesson = null)
            } else {
                it.copy(page = DiscoverPage.Review)
            }
        }
        if (lesson.status == LessonStatus.READ) return
        viewModelScope.launch {
            try {
                api.markRead(lesson.id)
            } catch (e: DiscoverApiException) {
                showMessage(DiscoverMessage.Error(DiscoverError.from(e)))
            }
            if (_uiState.value.page == DiscoverPage.Overview) refresh()
        }
    }

    /** Skips reading, straight to the cards. Used for lessons that were read before. */
    fun startReview() {
        if (_uiState.value.lesson?.lesson == null) return
        _uiState.update { it.copy(page = DiscoverPage.Review) }
    }

    // ---- review ----

    fun approve(editedFields: Map<String, String>? = null) =
        decide { decisions, lesson, proposal -> decisions.approve(lesson.slug, proposal, editedFields) }

    fun sendFeedback(comment: String) = decide { decisions, _, proposal -> decisions.feedback(proposal, comment) }

    fun reject(
        reason: RejectReason,
        comment: String?,
    ) = decide { decisions, _, proposal -> decisions.reject(proposal, reason, comment) }

    private fun decide(action: suspend (ProposalDecisions, Lesson, Proposal) -> DecisionOutcome) {
        val lessonState = _uiState.value.lesson ?: return
        val lesson = lessonState.lesson ?: return
        val proposal = lessonState.currentProposal ?: return
        if (lessonState.busy) return
        val api = currentConfig()?.let(apiFactory) ?: return
        updateLesson(lesson.id) { it.copy(busy = true) }
        viewModelScope.launch {
            val outcome = action(decisions(api), lesson, proposal)
            val approved = outcome is DecisionOutcome.Decided && outcome.proposal.status == ProposalStatus.APPROVED
            val advance = outcome is DecisionOutcome.Decided || outcome is DecisionOutcome.AlreadyDecided
            updateLesson(lesson.id) {
                it.copy(
                    busy = false,
                    reviewIndex = if (advance) it.reviewIndex + 1 else it.reviewIndex,
                    approvedCount = if (approved) it.approvedCount + 1 else it.approvedCount,
                    savedLocally =
                        if (outcome is DecisionOutcome.SavedLocally) it.savedLocally + proposal.id else it.savedLocally - proposal.id,
                )
            }
            messageFor(outcome)?.let(::showMessage)
            val finished = _uiState.value.lesson?.reviewFinished == true
            if (finished && (_uiState.value.lesson?.approvedCount ?: 0) > 0) {
                _uiState.update { it.copy(syncRequested = true) }
            }
        }
    }

    private fun messageFor(outcome: DecisionOutcome): DiscoverMessage? =
        when (outcome) {
            is DecisionOutcome.Decided -> null
            is DecisionOutcome.SavedLocally -> DiscoverMessage.SavedLocally
            is DecisionOutcome.AlreadyDecided -> DiscoverMessage.AlreadyDecided(outcome.detail)
            is DecisionOutcome.MissingNotetype -> DiscoverMessage.MissingNotetype(outcome.notetype)
            is DecisionOutcome.UnknownFields -> DiscoverMessage.UnknownFields(outcome.notetype, outcome.fields)
            DecisionOutcome.CommentRequired -> DiscoverMessage.CommentRequired
            is DecisionOutcome.Failed -> DiscoverMessage.Error(DiscoverError.from(outcome.error))
        }

    fun syncStarted() = _uiState.update { it.copy(syncRequested = false) }

    // ---- navigation ----

    /** @return false when already on the overview, so the caller leaves the Discover screen */
    fun back(): Boolean {
        val page = _uiState.value.page
        if (page == DiscoverPage.Overview) return false
        val reviewedSomething = (_uiState.value.lesson?.reviewIndex ?: 0) > 0
        _uiState.update { it.copy(page = DiscoverPage.Overview, lesson = if (page == DiscoverPage.Settings) it.lesson else null) }
        if (page == DiscoverPage.Review || reviewedSomething) refresh()
        return true
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun showMessage(message: DiscoverMessage) = _uiState.update { it.copy(message = message) }

    private fun updateLesson(
        lessonId: Long,
        transform: (LessonState) -> LessonState,
    ) = _uiState.update { state ->
        val lesson = state.lesson
        if (lesson == null || lesson.lessonId != lessonId) state else state.copy(lesson = transform(lesson))
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val prefs = discoverPreferences(context)
            return viewModelFactory {
                initializer {
                    DiscoverViewModel(
                        settings = SharedPreferencesDiscoverSettings(prefs),
                        apiFactory = { OkHttpDiscoverApi(it) },
                        noteCreator = CollectionDiscoverNoteCreator,
                        pendingApprovals = SharedPreferencesPendingApprovalStore(prefs),
                    )
                }
            }
        }
    }
}
