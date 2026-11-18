// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import com.ichi2.anki.R
import com.ichi2.anki.discover.BASIC_NOTETYPE
import com.ichi2.anki.discover.LessonState
import com.ichi2.anki.discover.Proposal
import com.ichi2.anki.discover.RejectReason
import com.ichi2.anki.discover.reviewFieldGroups

/** Review of the proposals of one lesson: per card yes, change (edit or feedback) or no. */
@Composable
internal fun DiscoverReviewScreen(
    state: LessonState,
    contentPadding: PaddingValues,
    onApprove: (editedFields: Map<String, String>?) -> Unit,
    onFeedback: (String) -> Unit,
    onReject: (RejectReason, String?) -> Unit,
    onDone: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        val proposal = state.currentProposal
        if (proposal == null) {
            ReviewFinished(approvedCount = state.approvedCount, onDone = onDone)
            return@Box
        }
        // keyed on the proposal, so dialog state does not leak to the next card
        key(proposal.id) {
            ProposalReview(
                proposal = proposal,
                index = state.reviewIndex,
                total = state.toReview.size,
                busy = state.busy,
                savedLocally = proposal.id in state.savedLocally,
                onApprove = onApprove,
                onFeedback = onFeedback,
                onReject = onReject,
            )
        }
    }
}

private enum class ReviewDialog { None, Edit, Feedback, Reject }

@Composable
private fun ProposalReview(
    proposal: Proposal,
    index: Int,
    total: Int,
    busy: Boolean,
    savedLocally: Boolean,
    onApprove: (Map<String, String>?) -> Unit,
    onFeedback: (String) -> Unit,
    onReject: (RejectReason, String?) -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf(ReviewDialog.None) }
    var changeMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.discover_card_counter, index + 1, total), style = MaterialTheme.typography.labelLarge)
            Text(
                listOfNotNull(proposal.deck, proposal.notetype, proposal.step).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            displayFieldGroups(proposal).forEach { group -> FieldCard(group) }
            if (savedLocally) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Text(
                        stringResource(R.string.discover_saved_locally),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (savedLocally) {
                // the note exists already: the only way forward is reporting that same note
                Button(onClick = { onApprove(null) }, enabled = !busy) {
                    Text(stringResource(R.string.discover_saved_locally_retry))
                }
                return@Row
            }
            TextButton(onClick = { dialog = ReviewDialog.Reject }, enabled = !busy) {
                Text(stringResource(R.string.discover_reject))
            }
            Box {
                OutlinedButton(onClick = { changeMenuOpen = true }, enabled = !busy) {
                    Text(stringResource(R.string.discover_change))
                }
                DropdownMenu(expanded = changeMenuOpen, onDismissRequest = { changeMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.discover_change_edit)) },
                        onClick = {
                            changeMenuOpen = false
                            dialog = ReviewDialog.Edit
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.discover_change_feedback)) },
                        onClick = {
                            changeMenuOpen = false
                            dialog = ReviewDialog.Feedback
                        },
                    )
                }
            }
            Button(onClick = { onApprove(null) }, enabled = !busy) {
                Text(stringResource(R.string.discover_approve))
            }
        }
    }

    when (dialog) {
        ReviewDialog.None -> {}
        ReviewDialog.Edit ->
            EditFieldsDialog(
                fields = editOrder(proposal),
                onDismiss = { dialog = ReviewDialog.None },
                onConfirm = {
                    dialog = ReviewDialog.None
                    onApprove(it)
                },
            )
        ReviewDialog.Feedback ->
            FeedbackDialog(
                onDismiss = { dialog = ReviewDialog.None },
                onConfirm = {
                    dialog = ReviewDialog.None
                    onFeedback(it)
                },
            )
        ReviewDialog.Reject ->
            RejectDialog(
                onDismiss = { dialog = ReviewDialog.None },
                onConfirm = { reason, comment ->
                    dialog = ReviewDialog.None
                    onReject(reason, comment)
                },
            )
    }
}

/** For `Basic`: front and back. For other notetypes: grouped and ordered by [reviewFieldGroups]. */
@Composable
private fun displayFieldGroups(proposal: Proposal): List<List<Pair<String, String>>> =
    if (proposal.notetype == BASIC_NOTETYPE) {
        listOf(
            listOf(stringResource(R.string.front_field_name) to proposal.fields["Front"].orEmpty()),
            listOf(stringResource(R.string.back_field_name) to proposal.fields["Back"].orEmpty()),
        )
    } else {
        reviewFieldGroups(proposal.notetype, proposal.fields)
    }

/** One card; a scenario and its answer share a card. */
@Composable
private fun FieldCard(fields: List<Pair<String, String>>) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { (label, html) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    // fields may contain HTML, as in Anki
                    Text(AnnotatedString.fromHtml(html), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun ReviewFinished(
    approvedCount: Int,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.discover_review_done), style = MaterialTheme.typography.titleMedium)
        if (approvedCount > 0) {
            Text(pluralStringResource(R.plurals.discover_review_done_added, approvedCount, approvedCount))
        }
        Button(onClick = onDone) { Text(stringResource(R.string.discover_back_to_overview)) }
    }
}

/** The editor shows every field, in the same order as the review (scenario next to its answer). */
private fun editOrder(proposal: Proposal): Map<String, String> {
    val ordered = reviewFieldGroups(proposal.notetype, proposal.fields).flatten().toMap()
    return ordered + proposal.fields.filterKeys { it !in ordered }
}

@Composable
private fun EditFieldsDialog(
    fields: Map<String, String>,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, String>) -> Unit,
) {
    var edited by remember { mutableStateOf(fields) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_edit_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                edited.forEach { (name, value) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = { edited = edited + (name to it) },
                        label = { Text(name) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(edited) }) { Text(stringResource(R.string.discover_edit_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun FeedbackDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_feedback_title)) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp))
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.discover_feedback_send))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun RejectDialog(
    onDismiss: () -> Unit,
    onConfirm: (RejectReason, String?) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf<RejectReason?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    val canConfirm = reason != null && (reason?.requiresComment != true || comment.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discover_reject_title)) },
        text = {
            Column {
                RejectReason.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == option, onClick = { reason = option }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null)
                        Text(stringResource(option.label()), Modifier.padding(start = 8.dp))
                    }
                }
                if (reason == RejectReason.OTHER) {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text(stringResource(R.string.discover_reject_comment)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { reason?.let { onConfirm(it, comment.takeIf { c -> it == RejectReason.OTHER && c.isNotBlank() }) } },
                enabled = canConfirm,
            ) { Text(stringResource(R.string.discover_reject)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

private fun RejectReason.label(): Int =
    when (this) {
        RejectReason.BAD_CARD -> R.string.discover_reject_bad
        RejectReason.NOT_INTERESTING -> R.string.discover_reject_not_interesting
        RejectReason.ALREADY_KNOWN -> R.string.discover_reject_known
        RejectReason.DUPLICATE -> R.string.discover_reject_duplicate
        RejectReason.OTHER -> R.string.discover_reject_other
    }
