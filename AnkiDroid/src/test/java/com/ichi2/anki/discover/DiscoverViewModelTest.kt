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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** State emitted by [DiscoverViewModel]: stacks, a stack walk, a lesson and its review, and books. */
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

    private fun shownViewModel() = viewModel().also { it.onShown(DiscoverTab.Lessons) }

    private val DiscoverViewModel.state get() = uiState.value

    /** Reads the open lesson and answers every card with [decide]. */
    private fun DiscoverViewModel.readAndDecideAll(decide: DiscoverViewModel.() -> Unit) {
        finishReading()
        while (state.lesson?.reviewFinished == false) decide()
    }

    @Test
    fun `without settings the lists ask for configuration`() =
        runTest {
            settings.save("", "")
            val vm = viewModel()

            vm.onShown(DiscoverTab.Books)

            assertFalse(vm.state.isConfigured)
            assertEquals(
                DiscoverError.Kind.NotConfigured,
                vm.state.overview.error
                    ?.kind,
            )
        }

    @Test
    fun `lessons come as stacks in the order of the service`() =
        runTest {
            val vm = shownViewModel()

            val stacks = vm.state.overview.openStacks
            assertEquals(listOf("bron:3", "pad:ai", "los"), stacks.map { it.key })
            val book = stacks.first()
            assertEquals("Atomic Habits", book.title)
            assertEquals(2, book.doneCount)
            assertEquals(7, book.total)
            assertEquals(103L, book.nextLesson?.id)
            assertEquals("all lessons, so the progress counts read ones", listOf("all"), api.lessonFilters.distinct())
        }

    @Test
    fun `a 401 is shown on the lists`() =
        runTest {
            api.failWith = DiscoverApiException.Unauthorized()

            assertEquals(
                DiscoverError.Kind.Unauthorized,
                shownViewModel()
                    .state.overview.error
                    ?.kind,
            )
        }

    @Test
    fun `a double tap on process sends one request`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            api.processGate = gate
            val vm = viewModel()

            vm.processSource(5)
            vm.processSource(5)
            assertTrue(5L in vm.state.overview.processingSourceIds)
            gate.complete(Unit)

            assertEquals(1, api.processCalls)
            assertFalse(5L in vm.state.overview.processingSourceIds)
            assertEquals(DiscoverMessage.ProcessingStarted, vm.state.message)
        }

    @Test
    fun `process errors are told apart by their code`() =
        runTest {
            val vm = viewModel()

            fun kindAfter(error: DiscoverApiException): DiscoverError.Kind {
                api.processError = error
                vm.processSource(5)
                return assertIs<DiscoverMessage.Error>(vm.state.message).error.kind
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
    fun `continue opens the first lesson that is not done`() =
        runTest {
            val vm = shownViewModel()

            vm.openStack("bron:3")
            assertEquals(DiscoverPage.Stack, vm.state.lessonsPage)
            vm.continueStack()

            assertEquals(DiscoverPage.Lesson, vm.state.lessonsPage)
            assertEquals(103L, vm.state.lesson?.lessonId)
        }

    @Test
    fun `walking a stack goes from lesson to lesson and ends with one sync`() =
        runTest {
            val vm = shownViewModel()
            vm.openStack("bron:3")
            vm.continueStack()

            vm.readAndDecideAll { approve() }
            val next = assertIs<AfterLesson.Next>(vm.state.afterLesson)
            assertEquals(104L, next.lesson.id)
            assertEquals(4, next.number)
            assertEquals(7, next.total)
            assertFalse("no sync halfway through a stack", vm.state.syncRequested)
            assertEquals(3, vm.state.openStack?.doneCount)

            for (expected in 104L..107L) {
                vm.openNextLesson()
                assertEquals(expected, vm.state.lesson?.lessonId)
                vm.readAndDecideAll { reject(RejectReason.ALREADY_KNOWN, null) }
            }

            val finished = assertIs<AfterLesson.StackFinished>(vm.state.afterLesson)
            assertEquals("Atomic Habits", finished.title)
            assertEquals(1, finished.approvedInStack)
            assertTrue(vm.state.syncRequested)
            assertEquals(listOf(103L, 104L, 105L, 106L, 107L), api.readLessons)

            vm.syncStarted()
            vm.closeStack()
            assertEquals(DiscoverPage.Overview, vm.state.lessonsPage)
            assertNull(vm.state.openStackKey)
            assertFalse("nothing new to sync", vm.state.syncRequested)
        }

    @Test
    fun `a lesson without cards goes straight to the next lesson offer`() =
        runTest {
            api.cardsPerLesson = 0
            val vm = shownViewModel()
            vm.openStack("bron:3")
            vm.continueStack()

            vm.finishReading()

            assertEquals(DiscoverPage.Review, vm.state.lessonsPage)
            assertEquals(104L, assertIs<AfterLesson.Next>(vm.state.afterLesson).lesson.id)
        }

    @Test
    fun `leaving a stack halfway syncs what was approved`() =
        runTest {
            val vm = shownViewModel()
            vm.openStack("bron:3")
            vm.continueStack()
            vm.readAndDecideAll { approve() }

            assertTrue(vm.back(DiscoverTab.Lessons))

            assertEquals("back to the stack, not the list", DiscoverPage.Stack, vm.state.lessonsPage)
            assertTrue(vm.state.syncRequested)
        }

    @Test
    fun `a stack of one lesson opens that lesson`() =
        runTest {
            val vm = shownViewModel()

            vm.openStack("los")

            assertEquals(DiscoverPage.Lesson, vm.state.lessonsPage)
            assertEquals(301L, vm.state.lesson?.lessonId)
            vm.readAndDecideAll { approve() }
            assertIs<AfterLesson.StackFinished>(vm.state.afterLesson)
            assertTrue(vm.back(DiscoverTab.Lessons))
            assertEquals(DiscoverPage.Overview, vm.state.lessonsPage)
        }

    @Test
    fun `a refused yes shows the unlinked note, moves on and is listed until dismissed`() =
        runTest {
            val vm = shownViewModel()
            vm.openStack("los")
            vm.startReview()

            api.decideError = DiscoverApiException.Conflict("ander note-id", DiscoverErrorCode.NOTE_ID_MISMATCH)
            vm.approve()

            val noteId = col.findNotes("tag:les::los-1").single()
            assertEquals(DiscoverMessage.Orphaned(noteId, OrphanReason.NOTE_ID_MISMATCH), vm.state.message)
            assertEquals(1, assertNotNull(vm.state.lesson).reviewIndex)

            api.decideError = null
            vm.back(DiscoverTab.Lessons)
            assertEquals(
                listOf(noteId),
                vm.state.overview.orphans
                    .map { it.noteId },
            )

            vm.dismissOrphan(30101)
            assertTrue(
                vm.state.overview.orphans
                    .isEmpty(),
            )
            assertEquals("the note stays in Anki", 1, col.noteCount())
        }

    @Test
    fun `missing notetype keeps the card open and adds nothing`() =
        runTest {
            api.notetype = "Brainiac Toepassing"
            val vm = shownViewModel()
            vm.openStack("los")
            vm.startReview()

            vm.approve()

            assertEquals(DiscoverMessage.MissingNotetype("Brainiac Toepassing"), vm.state.message)
            assertEquals(0, assertNotNull(vm.state.lesson).reviewIndex)
            assertEquals(0, col.noteCount())
        }

    @Test
    fun `an offline yes marks the card as saved locally and a retry reports the same note`() =
        runTest {
            val vm = shownViewModel()
            vm.openStack("los")
            vm.startReview()

            api.failWith = DiscoverApiException.Network(java.io.IOException("offline"))
            vm.approve()
            assertEquals(DiscoverMessage.SavedLocally, vm.state.message)
            assertTrue(30101L in assertNotNull(vm.state.lesson).savedLocally)

            api.failWith = null
            vm.approve()

            assertEquals(1, col.noteCount())
            assertEquals(1, assertNotNull(vm.state.lesson).reviewIndex)
        }

    @Test
    fun `dismissing and restoring a book`() =
        runTest {
            val vm = shownViewModel()
            vm.openBook(5)
            assertEquals(DiscoverPage.BookDetail, vm.state.booksPage)
            assertEquals("Thinking in Systems", vm.state.book?.title)

            vm.dismissBook(5, " te theoretisch ")
            assertEquals(SourceStatus.DISMISSED, vm.state.book?.status)
            assertEquals(DiscoverMessage.BookDismissed, vm.state.message)
            assertEquals("te theoretisch", api.dismissComments.single())

            vm.restoreBook(5)
            assertEquals(SourceStatus.QUEUED, vm.state.book?.status)
            assertEquals(DiscoverMessage.BookRestored, vm.state.message)
        }

    @Test
    fun `dismissing a book that is no longer queued shows the error and the current status`() =
        runTest {
            val vm = shownViewModel()
            api.statusOnServer(5, SourceStatus.PROCESSING)

            vm.dismissBook(5, null)

            assertEquals(DiscoverError.Kind.SourceNotQueued, assertIs<DiscoverMessage.Error>(vm.state.message).error.kind)
            assertEquals(
                SourceStatus.PROCESSING,
                vm.state.overview
                    .source(5)
                    ?.status,
            )
            assertTrue(
                vm.state.overview.busySourceIds
                    .isEmpty(),
            )
        }

    @Test
    fun `a processed book leads to its stack on the Lessons tab`() =
        runTest {
            val vm = shownViewModel()

            vm.openBookLessons(3)

            assertEquals(DiscoverTab.Lessons, vm.state.switchToTab)
            assertEquals("bron:3", vm.state.openStackKey)
            assertEquals(DiscoverPage.Stack, vm.state.lessonsPage)
            vm.tabSwitched()
            assertNull(vm.state.switchToTab)
        }

    @Test
    fun `settings and back are per tab`() =
        runTest {
            val vm = shownViewModel()
            vm.openBook(5)
            vm.openSettings(DiscoverTab.Lessons)

            assertEquals(DiscoverPage.Settings, vm.state.lessonsPage)
            assertEquals(DiscoverPage.BookDetail, vm.state.booksPage)
            assertTrue(vm.back(DiscoverTab.Books))
            assertEquals(DiscoverPage.Overview, vm.state.booksPage)
            assertEquals(DiscoverPage.Settings, vm.state.lessonsPage)
            assertFalse(vm.back(DiscoverTab.Books))
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

/**
 * A [DiscoverApi] with three stacks, in the order the service sends them: the book "Atomic
 * Habits" (lessons 101-107, the first two done), the AI path (201, 202) and one loose lesson (301).
 * Each lesson has [cardsPerLesson] proposals with ids `lessonId * 100 + n`. Books: 3 (processed)
 * and 5 (queued).
 */
private class ScriptedDiscoverApi : DiscoverApi {
    var failWith: DiscoverApiException? = null
    var decideError: DiscoverApiException? = null
    var processError: DiscoverApiException? = null
    var processGate: CompletableDeferred<Unit>? = null
    var processCalls = 0
    var cardsPerLesson = 1
    var notetype = "Basic"
    val decisions = mutableListOf<Pair<Long, DecisionRequest>>()
    val readLessons = mutableListOf<Long>()
    val lessonFilters = mutableListOf<String>()
    val dismissComments = mutableListOf<String?>()

    private val bookStack = LessonStackRef("bron:3", "Atomic Habits", StackKind.BOOK)
    private val pathStack = LessonStackRef("pad:ai", "AI-pad", StackKind.PATH)
    private val looseStack = LessonStackRef("los", "Losse lessen", StackKind.LOOSE)

    private val lessons =
        (
            (1..7).map { n ->
                summary(100L + n, "Idee $n", bookStack, position = n, sourceId = 3, done = n <= 2)
            } +
                listOf(summary(201, "Tokens", pathStack), summary(202, "Embeddings", pathStack), summary(301, "Los", looseStack))
        ).toMutableList()

    private val sources =
        mutableListOf(
            Source(id = 5, title = "Thinking in Systems", status = SourceStatus.QUEUED, topic = "systemen"),
            Source(id = 3, title = "Atomic Habits", status = SourceStatus.PROCESSED, lessonCount = 7),
        )

    private fun summary(
        id: Long,
        title: String,
        stack: LessonStackRef,
        position: Int? = null,
        sourceId: Long? = null,
        done: Boolean = false,
    ) = LessonSummary(
        id = id,
        slug = if (stack.kind == StackKind.LOOSE) "los-1" else "les-$id",
        title = title,
        status = if (done) LessonStatus.READ else LessonStatus.NEW,
        position = position,
        sourceId = sourceId,
        stack = stack,
        proposalCounts = ProposalCounts(new = if (done) 0 else 1),
    )

    private fun check() = failWith?.let { throw it }

    fun statusOnServer(
        sourceId: Long,
        status: SourceStatus,
    ) {
        sources.replaceAll { if (it.id == sourceId) it.copy(status = status) else it }
    }

    private fun proposalsOf(lessonId: Long) =
        (1..cardsPerLesson)
            .map { n -> basicProposal(id = lessonId * 100 + n, notetype = notetype).copy(lessonId = lessonId) }
            .filter { p -> decisions.none { it.first == p.id } }

    override suspend fun openLessons(): List<LessonSummary> = throw UnsupportedOperationException("the app asks for all lessons")

    override suspend fun allLessons(): List<LessonSummary> {
        check()
        lessonFilters.add("all")
        return lessons.map { s ->
            s.copy(
                proposalCounts =
                    ProposalCounts(
                        new =
                            if (s.status == LessonStatus.READ &&
                                s.proposalCounts.new == 0
                            ) {
                                0
                            } else {
                                proposalsOf(s.id).size
                            },
                    ),
            )
        }
    }

    override suspend fun lesson(id: Long): Lesson {
        check()
        val s = lessons.first { it.id == id }
        return Lesson(
            id = id,
            slug = s.slug,
            title = s.title,
            status = s.status,
            stack = s.stack,
            screens = listOf(LessonScreen("Kop", body = "Tekst")),
            proposals = proposalsOf(id),
        )
    }

    override suspend fun markRead(lessonId: Long): Lesson {
        check()
        readLessons.add(lessonId)
        lessons.replaceAll { if (it.id == lessonId) it.copy(status = LessonStatus.READ) else it }
        return lesson(lessonId)
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
        return sources.toList()
    }

    override suspend fun addSource(request: NewSourceRequest): Source {
        check()
        return Source(id = 9, title = request.title, status = SourceStatus.QUEUED).also { sources.add(it) }
    }

    override suspend fun process(sourceId: Long): ProcessResponse {
        processCalls++
        processGate?.await()
        processError?.let { throw it }
        return ProcessResponse(null)
    }

    override suspend fun source(id: Long): Source {
        check()
        return sources.firstOrNull { it.id == id } ?: throw DiscoverApiException.NotFound("bron", DiscoverErrorCode.NOT_FOUND)
    }

    override suspend fun dismiss(
        sourceId: Long,
        comment: String?,
    ): Source {
        check()
        val source = source(sourceId)
        if (!source.canDismiss) throw DiscoverApiException.Conflict("niet in de wachtrij", DiscoverErrorCode.SOURCE_NOT_QUEUED)
        dismissComments.add(comment)
        statusOnServer(sourceId, SourceStatus.DISMISSED)
        return source(sourceId)
    }

    override suspend fun restore(sourceId: Long): Source {
        check()
        if (!source(sourceId).canRestore) throw DiscoverApiException.Conflict("niet afgewezen")
        statusOnServer(sourceId, SourceStatus.QUEUED)
        return source(sourceId)
    }
}
