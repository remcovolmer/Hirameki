// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Data model of the Brainiac service REST API (v1).
 *
 * The service contract is the source of truth; field names follow it exactly via [SerialName].
 * Unknown fields are ignored and unknown enum values fall back to the declared default, so a newer
 * service does not break an older app.
 */

/** JSON configuration shared by the API client and the local stores. */
internal val discoverJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

@Serializable
enum class SourceKind {
    @SerialName("boek")
    BOOK,

    @SerialName("leerpad")
    LEARNING_PATH,

    @SerialName("overig")
    OTHER,
}

@Serializable
enum class SourceStatus {
    @SerialName("wachtrij")
    QUEUED,

    @SerialName("bezig")
    PROCESSING,

    @SerialName("verwerkt")
    PROCESSED,

    @SerialName("mislukt")
    FAILED,

    UNKNOWN,
}

@Serializable
data class Source(
    val id: Long,
    val title: String,
    val author: String? = null,
    val kind: SourceKind = SourceKind.BOOK,
    val status: SourceStatus = SourceStatus.UNKNOWN,
    val note: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("processed_at") val processedAt: String? = null,
    @SerialName("last_fired_at") val lastFiredAt: String? = null,
) {
    /** The service accepts `process` only for sources in these states (otherwise 409). */
    val canProcess: Boolean get() = status == SourceStatus.QUEUED || status == SourceStatus.FAILED
}

@Serializable
enum class LessonStatus {
    @SerialName("nieuw")
    NEW,

    @SerialName("gelezen")
    READ,

    UNKNOWN,
}

@Serializable
data class ProposalCounts(
    @SerialName("nieuw") val new: Int = 0,
    @SerialName("goedgekeurd") val approved: Int = 0,
    @SerialName("afgewezen") val rejected: Int = 0,
    @SerialName("feedback") val feedback: Int = 0,
)

@Serializable
data class LessonSummary(
    val id: Long,
    val slug: String,
    val track: String = "",
    val title: String,
    val subtitle: String? = null,
    val status: LessonStatus = LessonStatus.UNKNOWN,
    @SerialName("source_id") val sourceId: Long? = null,
    @SerialName("proposal_counts") val proposalCounts: ProposalCounts = ProposalCounts(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class LessonScreen(
    val heading: String? = null,
    val body: String = "",
)

@Serializable
data class Citation(
    val title: String,
    val url: String,
    val checked: String? = null,
)

@Serializable
data class Lesson(
    val id: Long,
    val slug: String,
    val track: String = "",
    val title: String,
    val subtitle: String? = null,
    val status: LessonStatus = LessonStatus.UNKNOWN,
    @SerialName("source_id") val sourceId: Long? = null,
    val screens: List<LessonScreen> = emptyList(),
    val citations: List<Citation> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
enum class ProposalKind {
    @SerialName("uitleg")
    EXPLAIN,

    @SerialName("toepas")
    APPLY,

    @SerialName("feit")
    FACT,

    @SerialName("contrast")
    CONTRAST,

    UNKNOWN,
}

@Serializable
enum class ProposalStatus {
    @SerialName("nieuw")
    NEW,

    @SerialName("goedgekeurd")
    APPROVED,

    @SerialName("afgewezen")
    REJECTED,

    @SerialName("feedback")
    FEEDBACK,

    UNKNOWN,
}

/** Reason for rejecting a proposal; [apiValue] is the `reject_reason` of the contract. */
enum class RejectReason(
    val apiValue: String,
) {
    BAD_CARD("slecht"),
    NOT_INTERESTING("niet_interessant"),
    ALREADY_KNOWN("weet_ik_al"),
    DUPLICATE("dubbel"),
    OTHER("anders"),
    ;

    /** The contract requires a comment for [OTHER]. */
    val requiresComment: Boolean get() = this == OTHER
}

@Serializable
data class Proposal(
    val id: Long,
    @SerialName("lesson_id") val lessonId: Long,
    val kind: ProposalKind = ProposalKind.UNKNOWN,
    val deck: String,
    val notetype: String,
    val fields: Map<String, String> = emptyMap(),
    val tags: List<String> = emptyList(),
    val step: String? = null,
    val status: ProposalStatus = ProposalStatus.UNKNOWN,
    val revision: Int = 1,
    val comment: String? = null,
    @SerialName("anki_note_id") val ankiNoteId: Long? = null,
)

/** Body of `POST /api/v1/proposals/{id}/decision`. Null values are omitted. */
@Serializable
data class DecisionRequest(
    val decision: String,
    @SerialName("anki_note_id") val ankiNoteId: Long? = null,
    val fields: Map<String, String>? = null,
    val reason: String? = null,
    val comment: String? = null,
) {
    companion object {
        fun approve(
            noteId: Long,
            editedFields: Map<String, String>?,
        ) = DecisionRequest(decision = "ja", ankiNoteId = noteId, fields = editedFields)

        fun feedback(comment: String) = DecisionRequest(decision = "feedback", comment = comment)

        fun reject(
            reason: RejectReason,
            comment: String?,
        ) = DecisionRequest(decision = "nee", reason = reason.apiValue, comment = comment?.takeIf { it.isNotBlank() })
    }
}

/** Body of `POST /api/v1/sources`. */
@Serializable
data class NewSourceRequest(
    val title: String,
    val author: String? = null,
    val kind: SourceKind? = null,
    val note: String? = null,
)

@Serializable
data class ProcessResponse(
    @SerialName("session_url") val sessionUrl: String? = null,
)

@Serializable
internal data class LessonsResponse(
    val lessons: List<LessonSummary> = emptyList(),
)

@Serializable
internal data class SourcesResponse(
    val sources: List<Source> = emptyList(),
)

@Serializable
internal data class ErrorResponse(
    val detail: String? = null,
    val code: String? = null,
)
