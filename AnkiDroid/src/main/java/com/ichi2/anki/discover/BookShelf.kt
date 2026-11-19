// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

/** The status filter of the Books tab. Dismissed books have their own collapsed section. */
enum class BookFilter {
    /** Waiting for a decision or to be processed (also failed ones, which can be processed again). */
    ToReview,
    Processing,
    Processed,
    ;

    fun matches(source: Source): Boolean =
        when (this) {
            ToReview ->
                source.status == SourceStatus.QUEUED ||
                    source.status == SourceStatus.FAILED ||
                    source.status == SourceStatus.UNKNOWN
            Processing -> source.status == SourceStatus.PROCESSING
            Processed -> source.status == SourceStatus.PROCESSED
        }
}

/** Which kind of source the Books tab shows; only offered once the list holds a paper. */
enum class KindFilter {
    All,
    Books,
    Papers,
    ;

    fun matches(source: Source): Boolean =
        when (this) {
            All -> true
            Books -> !source.isPaper
            Papers -> source.isPaper
        }
}

/** Books under one topic; [topic] is null for books without one ("Other"). */
data class TopicGroup(
    val topic: String?,
    val books: List<Source>,
)

/**
 * Groups [sources] by topic, in the order topics first appear (the service sends newest first);
 * books without a topic come last. Topics are compared without case and surrounding spaces.
 */
fun groupByTopic(sources: List<Source>): List<TopicGroup> {
    val (withTopic, without) = sources.partition { !it.topic.isNullOrBlank() }
    val groups =
        withTopic
            .groupBy { it.topic!!.trim().lowercase() }
            .values
            .map { books -> TopicGroup(books.first().topic!!.trim(), books) }
    return if (without.isEmpty()) groups else groups + TopicGroup(null, without)
}

data class BookShelf(
    val groups: List<TopicGroup>,
    val dismissed: List<Source>,
    /** Per status, within the chosen kind. */
    val counts: Map<BookFilter, Int>,
    /** Whether to offer the kind filter at all: only when there are papers. */
    val hasPapers: Boolean,
)

fun bookShelf(
    sources: List<Source>,
    filter: BookFilter,
    kind: KindFilter = KindFilter.All,
): BookShelf {
    val ofKind = sources.filter { kind.matches(it) }
    return BookShelf(
        groups = groupByTopic(ofKind.filter { filter.matches(it) }),
        dismissed = ofKind.filter { it.status == SourceStatus.DISMISSED },
        counts = BookFilter.entries.associateWith { f -> ofKind.count { f.matches(it) } },
        hasPapers = sources.any { it.isPaper },
    )
}

/** How many lessons a source gives: an estimate before processing, the real number after. */
sealed interface LessonCount {
    val count: Int

    data class Expected(
        override val count: Int,
    ) : LessonCount

    data class Actual(
        override val count: Int,
    ) : LessonCount
}

/**
 * The lesson count to show for this source, or null when there is nothing worth showing. A
 * processed source shows its real lessons (falling back on the estimate the routine updates);
 * any other source shows the estimate.
 */
fun Source.lessonCountHint(): LessonCount? {
    val expected = expectedLessons?.takeIf { it > 0 }
    if (status == SourceStatus.PROCESSED) {
        val actual = lessonCount.takeIf { it > 0 } ?: expected ?: return null
        return LessonCount.Actual(actual)
    }
    return expected?.let { LessonCount.Expected(it) }
}

/** The "Left out" text of a processed source, if the routine left ideas out. */
fun Source.leftOut(): String? = omitted?.trim()?.takeIf { it.isNotEmpty() && status == SourceStatus.PROCESSED }

/** Whether "Add title" may send: a title, and for a paper a valid https link (the service requires one). */
fun canAddSource(
    title: String,
    kind: SourceKind,
    url: String,
): Boolean {
    if (title.isBlank()) return false
    val link = httpsUrlOrNull(url)
    return if (kind == SourceKind.PAPER) link != null else url.isBlank() || link != null
}

/**
 * The surname of the first author, for the small paper placeholder: "Adam Tauman Kalai e.a." gives
 * "Kalai", "Charles Conn, Robert McLean" gives "Conn".
 */
fun Source.firstAuthorSurname(): String? =
    author
        ?.substringBefore(',')
        ?.replace(Regex("""\s+(e\.a\.|et al\.?)$"""), "")
        ?.trim()
        ?.split(Regex("""\s+"""))
        ?.lastOrNull()
        ?.takeIf { it.isNotEmpty() }

/** "Vaswani e.a. · 2017": first author and year, for a paper's placeholder. */
fun Source.paperByline(): String? =
    listOfNotNull(author?.trim()?.takeIf { it.isNotEmpty() }, year?.toString()).joinToString(" · ").ifEmpty { null }

/** The first line of a summary, for the list. */
fun Source.summaryFirstLine(): String? =
    summary
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.isNotEmpty() }

/** "Initials" for a cover placeholder: the first letters of the first two words of the title. */
fun Source.initials(): String =
    title
        .split(Regex("\\s+"))
        .filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "?" }
