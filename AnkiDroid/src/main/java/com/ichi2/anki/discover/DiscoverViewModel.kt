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
import timber.log.Timber

enum class DiscoverTab { Lessons, Books }

/** The pages of a tab: Lessons uses Overview, Settings, Stack, Lesson and Review; Books uses Overview, Settings and BookDetail. */
enum class DiscoverPage { Overview, Settings, Stack, Lesson, Review, BookDetail }

/** An API failure, reduced to what the UI shows. */
data class DiscoverError(
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind {
        NotConfigured,
        Network,
        Unauthorized,
        NotFound,

        /** A 409 without a code the app knows. */
        Conflict,
        ProposalNotNew,
        DoubleTap,
        SourceNotQueued,
        RoutineNotConfigured,
        RoutineFailed,

        /** A 503 without a code the app knows. */
        ServiceUnavailable,
        Server,
    }

    companion object {
        /** Decides on the service's error `code`; falls back to the HTTP status when it is missing. */
        fun from(e: DiscoverApiException): DiscoverError {
            val byCode =
                when (e.errorCode) {
                    DiscoverErrorCode.DOUBLE_TAP -> Kind.DoubleTap
                    DiscoverErrorCode.SOURCE_NOT_QUEUED -> Kind.SourceNotQueued
                    // restore on a book that is not dismissed: the status changed elsewhere
                    DiscoverErrorCode.SOURCE_NOT_DISMISSED -> Kind.Conflict
                    DiscoverErrorCode.ROUTINE_NOT_CONFIGURED -> Kind.RoutineNotConfigured
                    DiscoverErrorCode.ROUTINE_FAILED -> Kind.RoutineFailed
                    DiscoverErrorCode.PROPOSAL_NOT_NEW, DiscoverErrorCode.NOTE_ID_MISMATCH -> Kind.ProposalNotNew
                    DiscoverErrorCode.UNAUTHORIZED -> Kind.Unauthorized
                    DiscoverErrorCode.NOT_FOUND -> Kind.NotFound
                    DiscoverErrorCode.INVALID, DiscoverErrorCode.INTERNAL, DiscoverErrorCode.POSITION_TAKEN, null -> null
                }
            return byCode?.let { DiscoverError(it, e.detail) } ?: fromStatus(e)
        }

        private fun fromStatus(e: DiscoverApiException) =
            when (e) {
                is DiscoverApiException.Network -> DiscoverError(Kind.Network)
                is DiscoverApiException.Unauthorized -> DiscoverError(Kind.Unauthorized)
                is DiscoverApiException.NotFound -> DiscoverError(Kind.NotFound, e.detail)
                is DiscoverApiException.Conflict -> DiscoverError(Kind.Conflict, e.detail)
                is DiscoverApiException.ServiceUnavailable -> DiscoverError(Kind.ServiceUnavailable, e.detail)
                is DiscoverApiException.InvalidRequest -> DiscoverError(Kind.Server, e.detail)
                is DiscoverApiException.Http -> DiscoverError(Kind.Server, e.detail ?: "HTTP ${e.status}")
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

    /** The service refused an approved card; its note stays in the collection without a link. */
    data class Orphaned(
        val noteId: Long,
        val reason: OrphanReason,
    ) : DiscoverMessage

    data class AlreadyDecided(
        val detail: String?,
    ) : DiscoverMessage

    data object CommentRequired : DiscoverMessage

    data object SourceAdded : DiscoverMessage

    data object ProcessingStarted : DiscoverMessage

    data object SettingsSaved : DiscoverMessage

    data object BookDismissed : DiscoverMessage

    data object BookRestored : DiscoverMessage
}

data class OverviewState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    /** All lessons (also read ones), in the order of the service: stack by stack. */
    val lessons: List<LessonSummary> = emptyList(),
    val sources: List<Source> = emptyList(),
    val error: DiscoverError? = null,
    /** Sources whose "process" call is in flight; their button is disabled against double taps. */
    val processingSourceIds: Set<Long> = emptySet(),
    /** Sources whose dismiss or restore call is in flight. */
    val busySourceIds: Set<Long> = emptySet(),
    val pendingApprovals: Int = 0,
    /** Approved notes the service refused; shown until dismissed. */
    val orphans: List<PendingApproval> = emptyList(),
) {
    val stacks: List<LessonStack> get() = groupIntoStacks(lessons)

    /** Stacks with something left to do, in the order of the service. */
    val openStacks: List<LessonStack> get() = stacks.filterNot { it.finished }
    val finishedStacks: List<LessonStack> get() = stacks.filter { it.finished }

    fun source(id: Long?): Source? = id?.let { sid -> sources.firstOrNull { it.id == sid } }
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

/** What the screen after a finished lesson offers. */
sealed interface AfterLesson {
    /** Another lesson of the stack is waiting; [number] is its 1-based place in the stack. */
    data class Next(
        val lesson: LessonSummary,
        val number: Int,
        val total: Int,
    ) : AfterLesson

    /** Every lesson of the stack is done (or the lesson stood alone). */
    data class StackFinished(
        val title: String,
        val approvedInStack: Int,
    ) : AfterLesson
}

data class DiscoverUiState(
    val lessonsPage: DiscoverPage = DiscoverPage.Overview,
    val booksPage: DiscoverPage = DiscoverPage.Overview,
    val serverUrl: String = "",
    val token: String = "",
    val overview: OverviewState = OverviewState(),
    /** The stack being walked through on the Lessons tab. */
    val openStackKey: String? = null,
    val lesson: LessonState? = null,
    /** Cards approved since the stack was opened, for the "Stack finished" screen. */
    val approvedInStack: Int = 0,
    /** Approved cards since the last sync request. */
    val unsyncedApprovals: Int = 0,
    val bookId: Long? = null,
    val bookFilter: BookFilter = BookFilter.ToReview,
    val kindFilter: KindFilter = KindFilter.All,
    val dismissedExpanded: Boolean = false,
    /** Set to ask the route to switch to another tab (e.g. from a book to its lessons). */
    val switchToTab: DiscoverTab? = null,
    val message: DiscoverMessage? = null,
    /** Set when a stack walk ends with approved cards; the route starts the app's sync. */
    val syncRequested: Boolean = false,
) {
    val isConfigured: Boolean get() = DiscoverConfig.parse(serverUrl, token) != null
    val config: DiscoverConfig? get() = DiscoverConfig.parse(serverUrl, token)
    val openStack: LessonStack? get() = openStackKey?.let { key -> overview.stacks.firstOrNull { it.key == key } }
    val book: Source? get() = overview.source(bookId)

    fun page(tab: DiscoverTab) = if (tab == DiscoverTab.Lessons) lessonsPage else booksPage

    /** What follows the open lesson once it is read and its cards are decided. */
    val afterLesson: AfterLesson?
        get() {
            val lessonId = lesson?.lessonId ?: return null
            val stack = openStack ?: return AfterLesson.StackFinished(lesson.title, approvedInStack)
            val next = stack.nextAfter(lessonId) ?: stack.nextLesson?.takeIf { it.id != lessonId }
            return if (next == null) {
                AfterLesson.StackFinished(stack.title, approvedInStack)
            } else {
                AfterLesson.Next(next, stack.numberOf(next.id), stack.total)
            }
        }
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
                overview =
                    pendingApprovals.all().let { all ->
                        OverviewState(pendingApprovals = all.count { it.awaitingReport }, orphans = all.filter { it.isOrphan })
                    },
            ),
        )
    val uiState: StateFlow<DiscoverUiState> = _uiState.asStateFlow()

    private fun currentConfig(): DiscoverConfig? = DiscoverConfig.parse(settings.serverUrl, settings.token)

    private fun decisions(api: DiscoverApi) = ProposalDecisions(api, noteCreator, pendingApprovals)

    // ---- overview ----

    /** Called when a tab is shown; reloads the lists unless something is open on that tab. */
    fun onShown(tab: DiscoverTab) {
        if (_uiState.value.page(tab) == DiscoverPage.Overview) refresh()
    }

    fun refresh() {
        val api = currentConfig()?.let(apiFactory)
        if (api == null) {
            _uiState.update { it.copy(overview = it.overview.copy(error = DiscoverError(DiscoverError.Kind.NotConfigured))) }
            return
        }
        _uiState.update { it.copy(overview = it.overview.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val pending = decisions(api).reportPendingApprovals()
            try {
                val lessons = api.allLessons()
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
                                pendingApprovals = pending.waiting,
                                orphans = pending.orphans,
                            ),
                    )
                }
            } catch (e: DiscoverApiException) {
                _uiState.update {
                    it.copy(
                        overview =
                            it.overview.copy(
                                loading = false,
                                error = DiscoverError.from(e),
                                pendingApprovals = pending.waiting,
                                orphans = pending.orphans,
                            ),
                    )
                }
            }
        }
    }

    // ---- books ----

    fun addSource(
        title: String,
        author: String?,
        kind: SourceKind,
        note: String?,
        url: String? = null,
    ) {
        val api = currentConfig()?.let(apiFactory) ?: return
        if (title.isBlank()) return
        // the service requires a link for a paper; the dialog does not allow adding one without
        val link = httpsUrlOrNull(url)
        if (kind == SourceKind.PAPER && link == null) return
        viewModelScope.launch {
            try {
                api.addSource(
                    NewSourceRequest(
                        title = title.trim(),
                        author = author?.trim()?.takeIf { it.isNotEmpty() },
                        kind = kind,
                        note = note?.trim()?.takeIf { it.isNotEmpty() },
                        url = link,
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

    fun setBookFilter(filter: BookFilter) = _uiState.update { it.copy(bookFilter = filter) }

    fun setKindFilter(filter: KindFilter) = _uiState.update { it.copy(kindFilter = filter) }

    fun toggleDismissed() = _uiState.update { it.copy(dismissedExpanded = !it.dismissedExpanded) }

    fun openBook(sourceId: Long) {
        _uiState.update { it.copy(booksPage = DiscoverPage.BookDetail, bookId = sourceId) }
        val api = currentConfig()?.let(apiFactory) ?: return
        viewModelScope.launch {
            // the list may be a while old; the detail shows the latest description
            try {
                replaceSource(api.source(sourceId))
            } catch (e: DiscoverApiException) {
                Timber.w(e, "Discover: could not refresh source %d", sourceId)
            }
        }
    }

    fun dismissBook(
        sourceId: Long,
        comment: String?,
    ) = changeBook(sourceId, DiscoverMessage.BookDismissed) { api -> api.dismiss(sourceId, comment?.trim()?.takeIf { it.isNotEmpty() }) }

    fun restoreBook(sourceId: Long) = changeBook(sourceId, DiscoverMessage.BookRestored) { api -> api.restore(sourceId) }

    private fun changeBook(
        sourceId: Long,
        success: DiscoverMessage,
        call: suspend (DiscoverApi) -> Source,
    ) {
        val api = currentConfig()?.let(apiFactory) ?: return
        if (sourceId in _uiState.value.overview.busySourceIds) return
        _uiState.update { it.copy(overview = it.overview.copy(busySourceIds = it.overview.busySourceIds + sourceId)) }
        viewModelScope.launch {
            try {
                replaceSource(call(api))
                showMessage(success)
            } catch (e: DiscoverApiException) {
                showMessage(DiscoverMessage.Error(DiscoverError.from(e)))
                // the status may have changed elsewhere: show the current one
                try {
                    replaceSource(api.source(sourceId))
                } catch (refresh: DiscoverApiException) {
                    Timber.w(refresh, "Discover: could not refresh source %d", sourceId)
                }
            } finally {
                _uiState.update { it.copy(overview = it.overview.copy(busySourceIds = it.overview.busySourceIds - sourceId)) }
            }
        }
    }

    private fun replaceSource(source: Source) =
        _uiState.update { state ->
            val sources = state.overview.sources
            val updated =
                if (sources.any { it.id == source.id }) {
                    sources.map {
                        if (it.id ==
                            source.id
                        ) {
                            source
                        } else {
                            it
                        }
                    }
                } else {
                    sources + source
                }
            state.copy(overview = state.overview.copy(sources = updated))
        }

    /** From a processed book to its lessons on the Lessons tab. */
    fun openBookLessons(sourceId: Long) {
        val stack =
            _uiState.value.overview.stacks
                .firstOrNull { it.sourceId == sourceId } ?: return
        _uiState.update { it.copy(switchToTab = DiscoverTab.Lessons) }
        openStack(stack.key)
    }

    fun tabSwitched() = _uiState.update { it.copy(switchToTab = null) }

    // ---- settings ----

    fun openSettings(tab: DiscoverTab) = setPage(tab, DiscoverPage.Settings)

    fun saveSettings(
        tab: DiscoverTab,
        serverUrl: String,
        token: String,
    ) {
        settings.save(serverUrl, token)
        _uiState.update {
            it.copy(
                lessonsPage = if (tab == DiscoverTab.Lessons) DiscoverPage.Overview else it.lessonsPage,
                booksPage = if (tab == DiscoverTab.Books) DiscoverPage.Overview else it.booksPage,
                serverUrl = settings.serverUrl,
                token = settings.token,
                overview = OverviewState(),
                message = DiscoverMessage.SettingsSaved,
            )
        }
        refresh()
    }

    // ---- stacks ----

    /** Opens a stack; a stack of one lesson opens that lesson directly. */
    fun openStack(stackKey: String) {
        val stack =
            _uiState.value.overview.stacks
                .firstOrNull { it.key == stackKey } ?: return
        _uiState.update { it.copy(openStackKey = stackKey, approvedInStack = 0, lesson = null) }
        if (stack.total == 1) {
            openLesson(stack.lessons.single())
        } else {
            _uiState.update { it.copy(lessonsPage = DiscoverPage.Stack) }
        }
    }

    /** "Continue": the first lesson of the open stack that is not done. */
    fun continueStack() {
        val next = _uiState.value.openStack?.nextLesson ?: return
        openLesson(next)
    }

    /** "Next lesson" after a finished lesson. */
    fun openNextLesson() {
        val next = (_uiState.value.afterLesson as? AfterLesson.Next)?.lesson ?: return
        openLesson(next)
    }

    /** Leaves a finished stack for the list of stacks. */
    fun closeStack() {
        _uiState.update { it.copy(lessonsPage = DiscoverPage.Overview, lesson = null, openStackKey = null) }
        requestSync()
        refresh()
    }

    // ---- lesson ----

    fun openLesson(summary: LessonSummary) {
        _uiState.update {
            it.copy(
                lessonsPage = DiscoverPage.Lesson,
                openStackKey = it.openStackKey ?: stackOf(summary).key,
                lesson = LessonState(lessonId = summary.id, title = summary.title),
            )
        }
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
                val savedLocally = toReview.filter { p -> pendingApprovals.get(p.id)?.awaitingReport == true }.map { it.id }.toSet()
                updateLesson(lessonId) {
                    it.copy(loading = false, lesson = lesson, toReview = toReview, savedLocally = savedLocally)
                }
            } catch (e: DiscoverApiException) {
                updateLesson(lessonId) { it.copy(loading = false, error = DiscoverError.from(e)) }
            }
        }
    }

    /**
     * Marks the lesson as read and moves on to its cards. A lesson without cards goes straight to
     * the "lesson done" screen, which offers the next lesson of the stack.
     */
    fun finishReading() {
        val lessonState = _uiState.value.lesson ?: return
        val lesson = lessonState.lesson ?: return
        val api = currentConfig()?.let(apiFactory) ?: return
        updateSummary(lesson.id) { it.copy(status = LessonStatus.READ) }
        _uiState.update { it.copy(lessonsPage = DiscoverPage.Review) }
        requestSyncIfStackFinished()
        if (lesson.status == LessonStatus.READ) return
        viewModelScope.launch {
            try {
                api.markRead(lesson.id)
            } catch (e: DiscoverApiException) {
                showMessage(DiscoverMessage.Error(DiscoverError.from(e)))
            }
        }
    }

    /** Skips reading, straight to the cards. Used for lessons that were read before. */
    fun startReview() {
        if (_uiState.value.lesson?.lesson == null) return
        _uiState.update { it.copy(lessonsPage = DiscoverPage.Review) }
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
            // an orphaned card is decided elsewhere: retrying is pointless, so move on
            val advance =
                outcome is DecisionOutcome.Decided || outcome is DecisionOutcome.AlreadyDecided || outcome is DecisionOutcome.Orphaned
            updateLesson(lesson.id) {
                it.copy(
                    busy = false,
                    reviewIndex = if (advance) it.reviewIndex + 1 else it.reviewIndex,
                    approvedCount = if (approved) it.approvedCount + 1 else it.approvedCount,
                    savedLocally =
                        if (outcome is DecisionOutcome.SavedLocally) it.savedLocally + proposal.id else it.savedLocally - proposal.id,
                )
            }
            if (advance) {
                // keep the stack's progress current without a reload
                updateSummary(lesson.id) { s ->
                    s.copy(proposalCounts = s.proposalCounts.copy(new = (s.proposalCounts.new - 1).coerceAtLeast(0)))
                }
            }
            if (approved) {
                _uiState.update { it.copy(approvedInStack = it.approvedInStack + 1, unsyncedApprovals = it.unsyncedApprovals + 1) }
            }
            messageFor(outcome)?.let(::showMessage)
            if (_uiState.value.lesson?.reviewFinished == true) requestSyncIfStackFinished()
        }
    }

    /** At the end of a stack walk, cards approved on the way are synced, as the sync button does. */
    private fun requestSyncIfStackFinished() {
        val state = _uiState.value
        if (state.lesson?.reviewFinished != true) return
        if (state.afterLesson is AfterLesson.StackFinished) requestSync()
    }

    private fun requestSync() =
        _uiState.update { if (it.unsyncedApprovals > 0) it.copy(syncRequested = true, unsyncedApprovals = 0) else it }

    private fun messageFor(outcome: DecisionOutcome): DiscoverMessage? =
        when (outcome) {
            is DecisionOutcome.Decided -> null
            is DecisionOutcome.SavedLocally -> DiscoverMessage.SavedLocally
            is DecisionOutcome.Orphaned -> DiscoverMessage.Orphaned(outcome.noteId, outcome.reason)
            is DecisionOutcome.AlreadyDecided -> DiscoverMessage.AlreadyDecided(outcome.detail)
            is DecisionOutcome.MissingNotetype -> DiscoverMessage.MissingNotetype(outcome.notetype)
            is DecisionOutcome.UnknownFields -> DiscoverMessage.UnknownFields(outcome.notetype, outcome.fields)
            DecisionOutcome.CommentRequired -> DiscoverMessage.CommentRequired
            is DecisionOutcome.Failed -> DiscoverMessage.Error(DiscoverError.from(outcome.error))
        }

    /** The user has seen the unlinked note of [proposalId]; the note itself stays in Anki. */
    fun dismissOrphan(proposalId: Long) {
        val api = currentConfig()?.let(apiFactory)
        if (api != null) {
            decisions(api).dismissOrphan(proposalId)
        } else {
            pendingApprovals.get(proposalId)?.takeIf { it.isOrphan }?.let { pendingApprovals.remove(proposalId) }
        }
        _uiState.update {
            it.copy(overview = it.overview.copy(orphans = it.overview.orphans.filterNot { o -> o.proposalId == proposalId }))
        }
    }

    fun syncStarted() = _uiState.update { it.copy(syncRequested = false) }

    // ---- navigation ----

    /** @return false when [tab] is on its list already, so the caller leaves the tab */
    fun back(tab: DiscoverTab): Boolean {
        val page = _uiState.value.page(tab)
        if (page == DiscoverPage.Overview) return false
        if (tab == DiscoverTab.Books) {
            setPage(tab, DiscoverPage.Overview)
            return true
        }
        val stack = _uiState.value.openStack
        val target =
            when {
                page == DiscoverPage.Settings -> DiscoverPage.Overview
                page == DiscoverPage.Stack -> DiscoverPage.Overview
                stack != null && stack.total > 1 -> DiscoverPage.Stack
                else -> DiscoverPage.Overview
            }
        _uiState.update {
            it.copy(
                lessonsPage = target,
                lesson = if (page == DiscoverPage.Settings) it.lesson else null,
                openStackKey = if (target == DiscoverPage.Overview && page != DiscoverPage.Settings) null else it.openStackKey,
            )
        }
        // leaving a lesson or stack: sync what was approved on the way, reload the progress
        if (page == DiscoverPage.Lesson || page == DiscoverPage.Review || page == DiscoverPage.Stack) {
            requestSync()
            refresh()
        }
        return true
    }

    fun messageShown() = _uiState.update { it.copy(message = null) }

    private fun showMessage(message: DiscoverMessage) = _uiState.update { it.copy(message = message) }

    private fun setPage(
        tab: DiscoverTab,
        page: DiscoverPage,
    ) = _uiState.update { if (tab == DiscoverTab.Lessons) it.copy(lessonsPage = page) else it.copy(booksPage = page) }

    private fun updateLesson(
        lessonId: Long,
        transform: (LessonState) -> LessonState,
    ) = _uiState.update { state ->
        val lesson = state.lesson
        if (lesson == null || lesson.lessonId != lessonId) state else state.copy(lesson = transform(lesson))
    }

    private fun updateSummary(
        lessonId: Long,
        transform: (LessonSummary) -> LessonSummary,
    ) = _uiState.update { state ->
        state.copy(overview = state.overview.copy(lessons = state.overview.lessons.map { if (it.id == lessonId) transform(it) else it }))
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
