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
