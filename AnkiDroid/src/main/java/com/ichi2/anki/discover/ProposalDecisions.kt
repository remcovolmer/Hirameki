// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import com.ichi2.anki.libanki.NoteId
import timber.log.Timber

sealed interface DecisionOutcome {
    /** The service accepted the decision. */
    data class Decided(
        val proposal: Proposal,
    ) : DecisionOutcome

    /**
     * The note exists in the collection, but reporting it failed (offline, 401, 5xx). A later
     * [ProposalDecisions.approve] or [ProposalDecisions.reportPendingApprovals] reports the same note.
     */
    data class SavedLocally(
        val noteId: NoteId,
        val error: DiscoverApiException,
    ) : DecisionOutcome

    /**
     * The note exists in the collection, but the service refused it (404 or 409). The note stays
     * in Anki without a link and is not reported again.
     */
    data class Orphaned(
        val noteId: NoteId,
        val reason: OrphanReason,
    ) : DecisionOutcome

    /** Feedback or "no" on a proposal that is no longer `nieuw`. Nothing was created. */
    data class AlreadyDecided(
        val detail: String?,
    ) : DecisionOutcome

    data class MissingNotetype(
        val notetype: String,
    ) : DecisionOutcome

    data class UnknownFields(
        val notetype: String,
        val fields: List<String>,
    ) : DecisionOutcome

    /** Feedback without text, or "other" without an explanation. Nothing was sent. */
    data object CommentRequired : DecisionOutcome

    data class Failed(
        val error: DiscoverApiException,
    ) : DecisionOutcome
}

/** Result of [ProposalDecisions.reportPendingApprovals]. */
data class PendingReport(
    /** Approvals whose report is still waiting (e.g. still offline). */
    val waiting: Int,
    /** Notes the service refused; shown until the user dismisses them. */
    val orphans: List<PendingApproval>,
)

/**
 * The decision logic of the Discover screen: yes / edit / feedback / no.
 *
 * For "yes" the note is created locally first and reported afterwards. A marker is stored before the
 * note is created and the note id right after, so neither a failed report nor an app death in
 * between leads to a second note. Repeating a "yes" with the same note id is idempotent on the
 * service, so a lost response is simply reported again.
 */
class ProposalDecisions(
    private val api: DiscoverApi,
    private val noteCreator: DiscoverNoteCreator,
    private val pending: PendingApprovalStore,
) {
    /**
     * Approves [proposal]. [editedFields] are the fields after the user edited them, or null to
     * accept the proposal as it is.
     */
    suspend fun approve(
        lessonSlug: String,
        proposal: Proposal,
        editedFields: Map<String, String>? = null,
    ): DecisionOutcome {
        val existing = pending.get(proposal.id)
        if (existing != null && existing.isOrphan) {
            return DecisionOutcome.Orphaned(existing.noteId!!, existing.orphanReason!!)
        }
        val approval =
            if (existing?.noteId != null) {
                Timber.i("Discover: proposal %d already has note %d, reporting again", proposal.id, existing.noteId)
                existing
            } else {
                // a marker from an earlier attempt fixes the fields: the note may already exist with them
                val reportFields =
                    if (existing != null) existing.editedFields else editedFields?.takeIf { it != proposal.fields }
                val spec =
                    NoteSpec(
                        deck = proposal.deck,
                        notetype = proposal.notetype,
                        fields = reportFields ?: proposal.fields,
                        tags = proposal.tags + lessonTag(lessonSlug),
                    )
                val noteId =
                    existing?.let { noteCreator.find(spec) }
                        ?: when (val result = createNote(proposal.id, spec, reportFields)) {
                            is NoteCreationResult.Created -> result.noteId
                            is NoteCreationResult.MissingNotetype -> return DecisionOutcome.MissingNotetype(result.notetype)
                            is NoteCreationResult.UnknownFields -> return DecisionOutcome.UnknownFields(result.notetype, result.fields)
                        }
                PendingApproval(proposalId = proposal.id, noteId = noteId, editedFields = reportFields).also { pending.put(it) }
            }
        return report(approval)
    }

    /** Creates the note behind a marker; the marker is dropped again when nothing was created. */
    private suspend fun createNote(
        proposalId: Long,
        spec: NoteSpec,
        reportFields: Map<String, String>?,
    ): NoteCreationResult {
        pending.put(PendingApproval(proposalId = proposalId, noteId = null, editedFields = reportFields))
        val result = noteCreator.create(spec)
        if (result !is NoteCreationResult.Created) pending.remove(proposalId)
        return result
    }

    suspend fun feedback(
        proposal: Proposal,
        comment: String,
    ): DecisionOutcome {
        if (comment.isBlank()) return DecisionOutcome.CommentRequired
        return send(proposal.id, DecisionRequest.feedback(comment.trim()))
    }

    suspend fun reject(
        proposal: Proposal,
        reason: RejectReason,
        comment: String? = null,
    ): DecisionOutcome {
        if (reason.requiresComment && comment.isNullOrBlank()) return DecisionOutcome.CommentRequired
        return send(proposal.id, DecisionRequest.reject(reason, comment?.trim()))
    }

    /** Reports every approval whose earlier report failed; refused ones become orphans. */
    suspend fun reportPendingApprovals(): PendingReport {
        for (approval in pending.all().filter { it.awaitingReport }) {
            report(approval)
        }
        val all = pending.all()
        return PendingReport(waiting = all.count { it.awaitingReport }, orphans = all.filter { it.isOrphan })
    }

    /** The user has seen that the note of [proposalId] is unlinked; forget it. The note stays. */
    fun dismissOrphan(proposalId: Long) {
        if (pending.get(proposalId)?.isOrphan == true) pending.remove(proposalId)
    }

    private suspend fun report(approval: PendingApproval): DecisionOutcome {
        val noteId = checkNotNull(approval.noteId)
        return try {
            val proposal = api.decide(approval.proposalId, DecisionRequest.approve(noteId, approval.editedFields))
            pending.remove(approval.proposalId)
            DecisionOutcome.Decided(proposal)
        } catch (e: DiscoverApiException.Conflict) {
            // a repeated "yes" with the same note id is a 200, so a 409 is a real conflict
            val reason =
                if (e.errorCode == DiscoverErrorCode.NOTE_ID_MISMATCH) OrphanReason.NOTE_ID_MISMATCH else OrphanReason.PROPOSAL_NOT_NEW
            orphan(approval, noteId, reason, e)
        } catch (e: DiscoverApiException.NotFound) {
            orphan(approval, noteId, OrphanReason.NOT_FOUND, e)
        } catch (e: DiscoverApiException) {
            Timber.w(e, "Discover: note %d created, report for proposal %d failed", noteId, approval.proposalId)
            DecisionOutcome.SavedLocally(noteId, e)
        }
    }

    private fun orphan(
        approval: PendingApproval,
        noteId: NoteId,
        reason: OrphanReason,
        e: DiscoverApiException,
    ): DecisionOutcome {
        Timber.w(e, "Discover: service refused note %d for proposal %d (%s)", noteId, approval.proposalId, reason)
        pending.put(approval.copy(orphanReason = reason))
        return DecisionOutcome.Orphaned(noteId, reason)
    }

    private suspend fun send(
        proposalId: Long,
        request: DecisionRequest,
    ): DecisionOutcome =
        try {
            DecisionOutcome.Decided(api.decide(proposalId, request))
        } catch (e: DiscoverApiException.Conflict) {
            DecisionOutcome.AlreadyDecided(e.detail)
        } catch (e: DiscoverApiException) {
            Timber.w(e, "Discover: decision '%s' for proposal %d failed", request.decision, proposalId)
            DecisionOutcome.Failed(e)
        }
}
