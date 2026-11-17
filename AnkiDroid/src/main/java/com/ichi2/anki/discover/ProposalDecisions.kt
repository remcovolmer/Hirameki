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
     * The note exists in the collection, but reporting it failed. A later [ProposalDecisions.approve]
     * or [ProposalDecisions.reportPendingApprovals] reports the same note again.
     */
    data class SavedLocally(
        val noteId: NoteId,
        val error: DiscoverApiException,
    ) : DecisionOutcome

    /** The service says the proposal is no longer `nieuw` (409); it was decided elsewhere or earlier. */
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

/**
 * The decision logic of the Discover screen: yes / edit / feedback / no.
 *
 * For "yes" the note is created locally first and reported afterwards. The proposal → note link is
 * stored before the service is called, so a failed report never leads to a second note.
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
        val approval =
            if (existing != null) {
                Timber.i("Discover: proposal %d already has note %d, reporting again", proposal.id, existing.noteId)
                existing
            } else {
                val fields = editedFields ?: proposal.fields
                val spec =
                    NoteSpec(
                        deck = proposal.deck,
                        notetype = proposal.notetype,
                        fields = fields,
                        tags = proposal.tags + lessonTag(lessonSlug),
                    )
                when (val result = noteCreator.create(spec)) {
                    is NoteCreationResult.MissingNotetype -> return DecisionOutcome.MissingNotetype(result.notetype)
                    is NoteCreationResult.UnknownFields -> return DecisionOutcome.UnknownFields(result.notetype, result.fields)
                    is NoteCreationResult.Created ->
                        PendingApproval(
                            proposalId = proposal.id,
                            noteId = result.noteId,
                            // the contract wants `fields` only when the user really changed them
                            editedFields = editedFields?.takeIf { it != proposal.fields },
                        ).also { pending.put(it) }
                }
            }
        return report(approval)
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

    fun pendingApproval(proposalId: Long): PendingApproval? = pending.get(proposalId)

    /**
     * Reports every approval whose earlier report failed.
     * @return the number of approvals still waiting afterwards
     */
    suspend fun reportPendingApprovals(): Int {
        for (approval in pending.all()) {
            report(approval)
        }
        return pending.all().size
    }

    private suspend fun report(approval: PendingApproval): DecisionOutcome =
        try {
            val proposal = api.decide(approval.proposalId, DecisionRequest.approve(approval.noteId, approval.editedFields))
            pending.remove(approval.proposalId)
            DecisionOutcome.Decided(proposal)
        } catch (e: DiscoverApiException.Conflict) {
            // most likely an earlier report did arrive but its response was lost
            pending.remove(approval.proposalId)
            DecisionOutcome.AlreadyDecided(e.detail)
        } catch (e: DiscoverApiException.NotFound) {
            pending.remove(approval.proposalId)
            DecisionOutcome.Failed(e)
        } catch (e: DiscoverApiException) {
            Timber.w(e, "Discover: note %d created, report for proposal %d failed", approval.noteId, approval.proposalId)
            DecisionOutcome.SavedLocally(approval.noteId, e)
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
