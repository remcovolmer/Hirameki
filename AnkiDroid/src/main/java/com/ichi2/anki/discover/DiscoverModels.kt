// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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

    @SerialName("paper")
    PAPER,
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

    /** The user is not interested; not processed and not suggested again. */
    @SerialName("afgewezen")
    DISMISSED,

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
    /** The core of the book in 2-4 sentences. */
    val summary: String? = null,
    @SerialName("key_points") val keyPoints: List<String> = emptyList(),
    /** Why this book may interest the user. */
    val why: String? = null,
    /** The direction the book was suggested under; the Books tab groups on it. */
    val topic: String? = null,
    val year: Int? = null,
    val isbn: String? = null,
    @SerialName("has_cover") val hasCover: Boolean = false,
    @SerialName("lesson_count") val lessonCount: Int = 0,
    /** Where the source can be found (arXiv page, publisher, author's site); required for papers. */
    val url: String? = null,
    /** Estimate of the lessons this source will give (1-12); the actual number once processed. */
    @SerialName("expected_lessons") val expectedLessons: Int? = null,
    /** After processing: which ideas were left out on purpose, and why. */
    val omitted: String? = null,
) {
    val isPaper: Boolean get() = kind == SourceKind.PAPER

    /** [url] when it is safe to open in a browser (https only), else null. */
    val openableUrl: String? get() = httpsUrlOrNull(url)

    /** The service accepts `process` and `dismiss` only for sources in these states (otherwise 409). */
    val canProcess: Boolean get() = status == SourceStatus.QUEUED || status == SourceStatus.FAILED
    val canDismiss: Boolean get() = canProcess
    val canRestore: Boolean get() = status == SourceStatus.DISMISSED
}

@Serializable
enum class StackKind {
    @SerialName("boek")
    BOOK,

    @SerialName("pad")
    PATH,

    @SerialName("los")
    LOOSE,

    @SerialName("paper")
    PAPER,
}

/** The stack a lesson belongs to: one book, one learning path, or the loose lessons. */
@Serializable
data class LessonStackRef(
    val key: String,
    val title: String,
    val kind: StackKind = StackKind.LOOSE,
)

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
    @SerialName("reading_minutes") val readingMinutes: Int? = null,
    val status: LessonStatus = LessonStatus.UNKNOWN,
    @SerialName("source_id") val sourceId: Long? = null,
    /** Place within the stack (1 = first); null for lessons without an order. */
    val position: Int? = null,
    @SerialName("source_title") val sourceTitle: String? = null,
    /** Missing on servers before 18-11; see [stackOf]. */
    val stack: LessonStackRef? = null,
    @SerialName("proposal_counts") val proposalCounts: ProposalCounts = ProposalCounts(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class LessonScreen(
    val heading: String? = null,
    @Serializable(with = LessonBlockListSerializer::class)
    val blocks: List<LessonBlock> = emptyList(),
    /** v1: the whole screen as text. v2 servers still send it as a plain-text fallback. */
    val body: String = "",
) {
    /** The blocks to show; a v1 screen (no blocks) becomes one text block. */
    val displayBlocks: List<LessonBlock>
        get() = blocks.ifEmpty { if (body.isBlank()) emptyList() else listOf(TextBlock(body)) }
}

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
    @SerialName("reading_minutes") val readingMinutes: Int? = null,
    val status: LessonStatus = LessonStatus.UNKNOWN,
    @SerialName("source_id") val sourceId: Long? = null,
    /** Place within the stack (1 = first); null for lessons without an order. */
    val position: Int? = null,
    @SerialName("source_title") val sourceTitle: String? = null,
    /** Missing on servers before 18-11; see [stackOf]. */
    val stack: LessonStackRef? = null,
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

/** Body of `POST /api/v1/sources/{id}/dismiss`. */
@Serializable
data class DismissRequest(
    val comment: String? = null,
)

/** Body of `POST /api/v1/sources`. */
@Serializable
data class NewSourceRequest(
    val title: String,
    val author: String? = null,
    val kind: SourceKind? = null,
    val note: String? = null,
    val url: String? = null,
)

/** [value] trimmed when it is a valid https URL with a host, else null. Plain http and other schemes are refused. */
fun httpsUrlOrNull(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    return if (url.isHttps && url.host.isNotEmpty()) trimmed else null
}

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
