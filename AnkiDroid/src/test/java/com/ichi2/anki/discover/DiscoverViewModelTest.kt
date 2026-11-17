// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.discover.DiscoverTestData.basicProposal
import com.ichi2.testutils.JvmTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** State emitted by [DiscoverViewModel] for the overview, a lesson and its review. */
@RunWith(AndroidJUnit4::class)
class DiscoverViewModelTest : JvmTest() {
    private val api = ScriptedDiscoverApi()
    private val settings = MemorySettings()

    private fun viewModel() =
        DiscoverViewModel(
            settings = settings,
            apiFactory = { api },
            noteCreator = TestCollectionNoteCreator { col },
            pendingApprovals = InMemoryPendingApprovalStore(),
        )

    @Test
    fun `without settings the overview asks for configuration`() =
        runTest {
            settings.save("", "")
            val vm = viewModel()

            vm.onShown()

            assertFalse(vm.uiState.value.isConfigured)
            assertEquals(
                DiscoverError.Kind.NotConfigured,
                vm.uiState.value.overview.error
                    ?.kind,
            )
        }

    @Test
    fun `overview shows open lessons and the unfinished queue`() =
        runTest {
            val vm = viewModel()

            vm.onShown()

            val overview = vm.uiState.value.overview
            assertTrue(overview.loaded)
            assertEquals(listOf("Habit stacking"), overview.lessons.map { it.title })
            assertEquals(listOf("Thinking in Systems"), overview.queue.map { it.title })
        }

    @Test
    fun `a 401 is shown on the overview`() =
        runTest {
            api.failWith = DiscoverApiException.Unauthorized()
            val vm = viewModel()

            vm.onShown()

            assertEquals(
                DiscoverError.Kind.Unauthorized,
                vm.uiState.value.overview.error
                    ?.kind,
            )
        }

    @Test
    fun `a double tap on process sends one request`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            api.processGate = gate
            val vm = viewModel()

            vm.processSource(3)
            vm.processSource(3)
            assertTrue(3L in vm.uiState.value.overview.processingSourceIds)
            gate.complete(Unit)

            assertEquals(1, api.processCalls)
            assertFalse(3L in vm.uiState.value.overview.processingSourceIds)
            assertEquals(DiscoverMessage.ProcessingStarted, vm.uiState.value.message)
        }

    @Test
    fun `a 503 on process is shown as a message`() =
        runTest {
            api.processError = DiscoverApiException.ServiceUnavailable("routine niet geconfigureerd")
            val vm = viewModel()

            vm.processSource(3)

            val message = assertIs<DiscoverMessage.Error>(vm.uiState.value.message)
            assertEquals(DiscoverError.Kind.ServiceUnavailable, message.error.kind)
        }

    @Test
    fun `process errors are told apart by their code`() =
        runTest {
            val vm = viewModel()

            fun kindAfter(error: DiscoverApiException): DiscoverError.Kind {
                api.processError = error
                vm.processSource(3)
                return assertIs<DiscoverMessage.Error>(vm.uiState.value.message).error.kind
            }

            assertEquals(DiscoverError.Kind.DoubleTap, kindAfter(DiscoverApiException.Conflict("x", DiscoverErrorCode.DOUBLE_TAP)))
            assertEquals(
                DiscoverError.Kind.SourceNotQueued,
                kindAfter(DiscoverApiException.Conflict("x", DiscoverErrorCode.SOURCE_NOT_QUEUED)),
            )
            assertEquals(
                DiscoverError.Kind.RoutineNotConfigured,
                kindAfter(DiscoverApiException.ServiceUnavailable("x", DiscoverErrorCode.ROUTINE_NOT_CONFIGURED)),
            )
            assertEquals(
                DiscoverError.Kind.RoutineFailed,
                kindAfter(DiscoverApiException.Http(502, "upstream", DiscoverErrorCode.ROUTINE_FAILED)),
            )
            assertEquals(DiscoverError.Kind.Conflict, kindAfter(DiscoverApiException.Conflict("zonder code")))
        }

    @Test
    fun `a refused yes shows the unlinked note, moves on and is listed until dismissed`() =
        runTest {
            val vm = viewModel()
            vm.onShown()
            vm.openLesson(
                vm.uiState.value.overview.lessons
                    .single(),
            )
            vm.startReview()

            api.decideError = DiscoverApiException.Conflict("ander note-id", DiscoverErrorCode.NOTE_ID_MISMATCH)
            vm.approve()

            val noteId = col.findNotes("tag:les::habit-stacking").single()
            assertEquals(DiscoverMessage.Orphaned(noteId, OrphanReason.NOTE_ID_MISMATCH), vm.uiState.value.message)
            assertEquals(
                1,
                vm.uiState.value.lesson!!
                    .reviewIndex,
            )

            api.decideError = null
            vm.back()
            assertEquals(
                listOf(noteId),
                vm.uiState.value.overview.orphans
                    .map { it.noteId },
            )
            assertEquals(0, vm.uiState.value.overview.pendingApprovals)

            vm.dismissOrphan(11)
            assertTrue(
                vm.uiState.value.overview.orphans
                    .isEmpty(),
            )
            vm.refresh()
            assertTrue(
                vm.uiState.value.overview.orphans
                    .isEmpty(),
            )
            assertEquals("the note stays in Anki", 1, col.noteCount())
        }

    @Test
    fun `reading a lesson, approving and rejecting, then sync is requested`() =
        runTest {
            val vm = viewModel()
            vm.onShown()

            vm.openLesson(
                vm.uiState.value.overview.lessons
                    .single(),
            )
            assertEquals(DiscoverPage.Lesson, vm.uiState.value.page)
            assertEquals(
                2,
                vm.uiState.value.lesson!!
                    .toReview.size,
            )

            vm.finishReading()
            assertEquals(DiscoverPage.Review, vm.uiState.value.page)
            assertEquals(listOf(7L), api.readLessons)

            vm.approve()
            assertEquals(1, col.noteCount())
            assertFalse("sync only after the whole lesson", vm.uiState.value.syncRequested)

            vm.reject(RejectReason.DUPLICATE, null)
            val lesson = vm.uiState.value.lesson!!
            assertTrue(lesson.reviewFinished)
            assertEquals(1, lesson.approvedCount)
            assertTrue(vm.uiState.value.syncRequested)

            vm.syncStarted()
            assertFalse(vm.uiState.value.syncRequested)
            assertTrue(vm.back())
            assertEquals(DiscoverPage.Overview, vm.uiState.value.page)
            assertNull(vm.uiState.value.lesson)
        }

    @Test
    fun `missing notetype keeps the card open and adds nothing`() =
        runTest {
            api.proposals = listOf(basicProposal(id = 21, notetype = "Brainiac Toepassing", fields = mapOf("Scenario1" to "a")))
            val vm = viewModel()
            vm.onShown()
            vm.openLesson(
                vm.uiState.value.overview.lessons
                    .single(),
            )
            vm.startReview()

            vm.approve()

            assertEquals(DiscoverMessage.MissingNotetype("Brainiac Toepassing"), vm.uiState.value.message)
            assertEquals(
                0,
                vm.uiState.value.lesson!!
                    .reviewIndex,
            )
            assertEquals(0, col.noteCount())
            assertTrue(api.decisions.isEmpty())
        }

    @Test
    fun `an offline yes marks the card as saved locally and a retry reports the same note`() =
        runTest {
            val vm = viewModel()
            vm.onShown()
            vm.openLesson(
                vm.uiState.value.overview.lessons
                    .single(),
            )
            vm.startReview()

            api.failWith = DiscoverApiException.Network(java.io.IOException("offline"))
            vm.approve()
            assertEquals(DiscoverMessage.SavedLocally, vm.uiState.value.message)
            assertTrue(
                11L in
                    vm.uiState.value.lesson!!
                        .savedLocally,
            )
            assertEquals(
                0,
                vm.uiState.value.lesson!!
                    .reviewIndex,
            )

            api.failWith = null
            vm.approve()

            assertEquals(1, col.noteCount())
            assertEquals(
                1,
                vm.uiState.value.lesson!!
                    .reviewIndex,
            )
            assertFalse(
                11L in
                    vm.uiState.value.lesson!!
                        .savedLocally,
            )
        }
}

private class MemorySettings : DiscoverSettings {
    override var serverUrl = "http://brainiac.test"
    override var token = "secret"

    override fun save(
        serverUrl: String,
        token: String,
    ) {
        this.serverUrl = serverUrl
        this.token = token
    }
}

/** A [DiscoverApi] with one lesson (proposals 11 and 12) and two sources. */
private class ScriptedDiscoverApi : DiscoverApi {
    var failWith: DiscoverApiException? = null
    var decideError: DiscoverApiException? = null
    var processError: DiscoverApiException? = null
    var processGate: CompletableDeferred<Unit>? = null
    var processCalls = 0
    var proposals = listOf(basicProposal(id = 11), basicProposal(id = 12))
    val decisions = mutableListOf<Pair<Long, DecisionRequest>>()
    val readLessons = mutableListOf<Long>()

    private fun check() = failWith?.let { throw it }

    private val summary =
        LessonSummary(id = 7, slug = "habit-stacking", title = "Habit stacking", status = LessonStatus.NEW)

    private fun lesson() =
        Lesson(
            id = 7,
            slug = "habit-stacking",
            title = "Habit stacking",
            status = LessonStatus.NEW,
            screens = listOf(LessonScreen("Kop", body = "Tekst")),
            proposals = proposals,
        )

    override suspend fun openLessons(): List<LessonSummary> {
        check()
        return listOf(summary)
    }

    override suspend fun lesson(id: Long): Lesson {
        check()
        return lesson()
    }

    override suspend fun markRead(lessonId: Long): Lesson {
        check()
        readLessons.add(lessonId)
        return lesson()
    }

    override suspend fun decide(
        proposalId: Long,
        request: DecisionRequest,
    ): Proposal {
        check()
        decideError?.let { throw it }
        decisions.add(proposalId to request)
        val status = if (request.decision == "ja") ProposalStatus.APPROVED else ProposalStatus.REJECTED
        return basicProposal(id = proposalId).copy(status = status)
    }

    override suspend fun sources(): List<Source> {
        check()
        return listOf(
            Source(id = 3, title = "Thinking in Systems", status = SourceStatus.QUEUED),
            Source(id = 4, title = "Atomic Habits", status = SourceStatus.PROCESSED),
        )
    }

    override suspend fun addSource(request: NewSourceRequest): Source {
        check()
        return Source(id = 5, title = request.title, status = SourceStatus.QUEUED)
    }

    override suspend fun process(sourceId: Long): ProcessResponse {
        processCalls++
        processGate?.await()
        processError?.let { throw it }
        return ProcessResponse(null)
    }
}
