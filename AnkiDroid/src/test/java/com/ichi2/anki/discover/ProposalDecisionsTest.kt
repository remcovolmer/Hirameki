// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import com.ichi2.anki.discover.DiscoverTestData.basicProposal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The decision logic: yes / edit / feedback / no, without a collection or network. */
class ProposalDecisionsTest {
    private val api = FakeDiscoverApi()
    private val pending = InMemoryPendingApprovalStore()
    private val created = mutableListOf<NoteSpec>()
    private var nextNoteId = 1000L
    private var creatorResult: ((NoteSpec) -> NoteCreationResult)? = null

    /** Simulates the app dying right after the note was created, before its id is stored. */
    private var dieAfterCreate = false
    private val creator =
        object : DiscoverNoteCreator {
            override suspend fun create(spec: NoteSpec): NoteCreationResult =
                creatorResult?.invoke(spec) ?: run {
                    created.add(spec)
                    val result = NoteCreationResult.Created(nextNoteId++)
                    if (dieAfterCreate) throw AppDied()
                    result
                }

            override suspend fun find(spec: NoteSpec): Long? = created.indexOfLast { it == spec }.takeIf { it >= 0 }?.let { 1000L + it }
        }
    private val decisions = ProposalDecisions(api, creator, pending)

    @Test
    fun `yes creates the note first, then reports its id`() =
        runTest {
            val outcome = decisions.approve("embeddings", basicProposal(tags = listOf("brainiac", "ai")))

            assertIs<DecisionOutcome.Decided>(outcome)
            val spec = created.single()
            assertEquals("Brainiac::AI-ML", spec.deck)
            assertEquals("Basic", spec.notetype)
            assertEquals("Wat is een embedding?", spec.fields["Front"])
            assertEquals(listOf("brainiac", "ai", "les::embeddings"), spec.tags)
            val (id, request) = api.decisions.single()
            assertEquals(11L, id)
            assertEquals(DecisionRequest.approve(1000, editedFields = null), request)
            assertNull(pending.get(11))
        }

    @Test
    fun `edit myself creates the edited note and reports the fields`() =
        runTest {
            val edited = mapOf("Front" to "Wat doet een embedding?", "Back" to "Een vector.")

            decisions.approve("embeddings", basicProposal(), edited)

            assertEquals(edited, created.single().fields)
            assertEquals(
                edited,
                api.decisions
                    .single()
                    .second.fields,
            )
        }

    @Test
    fun `opening the editor without changing anything counts as a plain yes`() =
        runTest {
            val proposal = basicProposal()

            decisions.approve("embeddings", proposal, proposal.fields)

            assertNull(
                api.decisions
                    .single()
                    .second.fields,
            )
        }

    @Test
    fun `a failed report keeps the note and a retry does not create a second one`() =
        runTest {
            api.failWith = DiscoverApiException.Network(IOException("offline"))

            val first = decisions.approve("embeddings", basicProposal())

            assertEquals(DecisionOutcome.SavedLocally(1000, api.failWith!!), first)
            assertEquals(PendingApproval(11, 1000, null), pending.get(11))

            api.failWith = null
            val second = decisions.approve("embeddings", basicProposal())

            assertIs<DecisionOutcome.Decided>(second)
            assertEquals("exactly one note", 1, created.size)
            assertEquals(
                1000L,
                api.decisions
                    .last()
                    .second.ankiNoteId,
            )
            assertNull(pending.get(11))
        }

    @Test
    fun `a retry reports the edited fields from the first attempt`() =
        runTest {
            val edited = mapOf("Front" to "anders", "Back" to "B")
            api.failWith = DiscoverApiException.Network(IOException("offline"))
            decisions.approve("embeddings", basicProposal(), edited)

            api.failWith = null
            decisions.approve("embeddings", basicProposal(), editedFields = null)

            assertEquals(1, created.size)
            assertEquals(
                edited,
                api.decisions
                    .last()
                    .second.fields,
            )
        }

    @Test
    fun `pending approvals are reported in the background`() =
        runTest {
            api.failWith = DiscoverApiException.Unauthorized()
            decisions.approve("embeddings", basicProposal(id = 11))
            decisions.approve("embeddings", basicProposal(id = 12))
            assertEquals(2, pending.all().size)

            api.failWith = null
            val report = decisions.reportPendingApprovals()

            assertEquals(0, report.waiting)
            assertEquals(2, created.size)
            assertEquals(setOf(1000L, 1001L), api.decisions.mapNotNull { it.second.ankiNoteId }.toSet())
        }

    @Test
    fun `a lost response is reported again and the idempotent 200 completes it`() =
        runTest {
            // the service stored the yes, but the response never arrived
            api.failAfterRecording = DiscoverApiException.Network(IOException("connection reset"))
            assertIs<DecisionOutcome.SavedLocally>(decisions.approve("embeddings", basicProposal()))

            api.failAfterRecording = null
            assertIs<DecisionOutcome.Decided>(decisions.approve("embeddings", basicProposal()))

            assertEquals(1, created.size)
            assertEquals(listOf(1000L, 1000L), api.decisions.map { it.second.ankiNoteId })
            assertNull(pending.get(11))
        }

    @Test
    fun `a 409 after creating the note orphans it and stops retrying`() =
        runTest {
            api.failWith = DiscoverApiException.Conflict("status is afgewezen", DiscoverErrorCode.PROPOSAL_NOT_NEW)

            val outcome = decisions.approve("embeddings", basicProposal())

            assertEquals(DecisionOutcome.Orphaned(1000, OrphanReason.PROPOSAL_NOT_NEW), outcome)
            val report = decisions.reportPendingApprovals()
            assertEquals(0, report.waiting)
            assertEquals(listOf(1000L), report.orphans.map { it.noteId })

            api.failWith = null
            assertEquals(DecisionOutcome.Orphaned(1000, OrphanReason.PROPOSAL_NOT_NEW), decisions.approve("embeddings", basicProposal()))
            assertTrue("never reported again", api.decisions.isEmpty())
            assertEquals("no second note", 1, created.size)

            decisions.dismissOrphan(11)
            assertNull(pending.get(11))
        }

    @Test
    fun `note id mismatch and 404 are orphans with their own reason`() =
        runTest {
            api.failWith = DiscoverApiException.Conflict("ander id", DiscoverErrorCode.NOTE_ID_MISMATCH)
            assertEquals(DecisionOutcome.Orphaned(1000, OrphanReason.NOTE_ID_MISMATCH), decisions.approve("x", basicProposal(id = 11)))

            api.failWith = DiscoverApiException.NotFound("weg", DiscoverErrorCode.NOT_FOUND)
            assertEquals(DecisionOutcome.Orphaned(1001, OrphanReason.NOT_FOUND), decisions.approve("x", basicProposal(id = 12)))

            api.failWith = DiscoverApiException.Conflict("oud formaat zonder code")
            assertEquals(DecisionOutcome.Orphaned(1002, OrphanReason.PROPOSAL_NOT_NEW), decisions.approve("x", basicProposal(id = 13)))
        }

    @Test
    fun `an app death between creating the note and storing its id does not create a second note`() =
        runTest {
            val edited = mapOf("Front" to "eigen versie", "Back" to "B")
            dieAfterCreate = true
            assertFailsWith<AppDied> { decisions.approve("embeddings", basicProposal(), edited) }
            assertEquals(PendingApproval(11, noteId = null, editedFields = edited), pending.get(11))

            dieAfterCreate = false
            assertIs<DecisionOutcome.Decided>(decisions.approve("embeddings", basicProposal()))

            assertEquals("found, not created again", 1, created.size)
            val request = api.decisions.single().second
            assertEquals(1000L, request.ankiNoteId)
            assertEquals(edited, request.fields)
        }

    @Test
    fun `a marker whose note was never created leads to a normal create`() =
        runTest {
            pending.put(PendingApproval(11, noteId = null))

            assertIs<DecisionOutcome.Decided>(decisions.approve("embeddings", basicProposal()))

            assertEquals(1, created.size)
        }

    @Test
    fun `feedback or no on a proposal that is no longer new is already decided`() =
        runTest {
            api.failWith = DiscoverApiException.Conflict("status is goedgekeurd", DiscoverErrorCode.PROPOSAL_NOT_NEW)

            assertEquals(DecisionOutcome.AlreadyDecided("status is goedgekeurd"), decisions.feedback(basicProposal(), "x"))
        }

    @Test
    fun `missing notetype creates nothing and reports nothing`() =
        runTest {
            creatorResult = { NoteCreationResult.MissingNotetype(it.notetype) }

            val outcome = decisions.approve("embeddings", basicProposal(notetype = "Brainiac Toepassing"))

            assertEquals(DecisionOutcome.MissingNotetype("Brainiac Toepassing"), outcome)
            assertTrue(api.decisions.isEmpty())
            assertTrue("marker removed again", pending.all().isEmpty())
        }

    @Test
    fun `unknown fields create nothing and report nothing`() =
        runTest {
            creatorResult = { NoteCreationResult.UnknownFields(it.notetype, listOf("Extra")) }

            val outcome = decisions.approve("embeddings", basicProposal())

            assertEquals(DecisionOutcome.UnknownFields("Basic", listOf("Extra")), outcome)
            assertTrue(api.decisions.isEmpty())
        }

    @Test
    fun `feedback needs text`() =
        runTest {
            assertEquals(DecisionOutcome.CommentRequired, decisions.feedback(basicProposal(), "  "))
            assertTrue(api.decisions.isEmpty())

            decisions.feedback(basicProposal(), " korter graag ")

            assertEquals(DecisionRequest.feedback("korter graag"), api.decisions.single().second)
            assertTrue("feedback never creates a note", created.isEmpty())
        }

    @Test
    fun `no with a reason, and other needs an explanation`() =
        runTest {
            assertEquals(DecisionOutcome.CommentRequired, decisions.reject(basicProposal(), RejectReason.OTHER, null))
            assertTrue(api.decisions.isEmpty())

            decisions.reject(basicProposal(), RejectReason.ALREADY_KNOWN)
            decisions.reject(basicProposal(), RejectReason.OTHER, "te vaag")

            val (known, other) = api.decisions.map { it.second }
            assertEquals("nee", known.decision)
            assertEquals("weet_ik_al", known.reason)
            assertEquals("anders", other.reason)
            assertEquals("te vaag", other.comment)
            assertTrue(created.isEmpty())
        }

    @Test
    fun `a failed feedback or no is reported as failed`() =
        runTest {
            api.failWith = DiscoverApiException.Network(IOException("offline"))
            assertIs<DecisionOutcome.Failed>(decisions.reject(basicProposal(), RejectReason.BAD_CARD))
            assertIs<DecisionOutcome.Failed>(decisions.feedback(basicProposal(), "x"))
        }
}

private class AppDied : RuntimeException("simulated process death")

class FakeDiscoverApi : DiscoverApi {
    val decisions = mutableListOf<Pair<Long, DecisionRequest>>()
    var failWith: DiscoverApiException? = null

    /** The service handles the request, but the response is lost. */
    var failAfterRecording: DiscoverApiException? = null

    override suspend fun decide(
        proposalId: Long,
        request: DecisionRequest,
    ): Proposal {
        failWith?.let { throw it }
        decisions.add(proposalId to request)
        failAfterRecording?.let { throw it }
        val status =
            when (request.decision) {
                "ja" -> ProposalStatus.APPROVED
                "nee" -> ProposalStatus.REJECTED
                else -> ProposalStatus.FEEDBACK
            }
        return basicProposal(id = proposalId).copy(status = status, ankiNoteId = request.ankiNoteId)
    }

    override suspend fun openLessons(): List<LessonSummary> = throw UnsupportedOperationException()

    override suspend fun lesson(id: Long): Lesson = throw UnsupportedOperationException()

    override suspend fun markRead(lessonId: Long): Lesson = throw UnsupportedOperationException()

    override suspend fun sources(): List<Source> = throw UnsupportedOperationException()

    override suspend fun addSource(request: NewSourceRequest): Source = throw UnsupportedOperationException()

    override suspend fun process(sourceId: Long): ProcessResponse = throw UnsupportedOperationException()
}

class InMemoryPendingApprovalStore : PendingApprovalStore {
    private val map = linkedMapOf<Long, PendingApproval>()

    override fun get(proposalId: Long) = map[proposalId]

    override fun all() = map.values.toList()

    override fun put(approval: PendingApproval) {
        map[approval.proposalId] = approval
    }

    override fun remove(proposalId: Long) {
        map.remove(proposalId)
    }
}
