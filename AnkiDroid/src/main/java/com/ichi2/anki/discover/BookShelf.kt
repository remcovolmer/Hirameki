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
    val counts: Map<BookFilter, Int>,
)

fun bookShelf(
    sources: List<Source>,
    filter: BookFilter,
): BookShelf =
    BookShelf(
        groups = groupByTopic(sources.filter { filter.matches(it) }),
        dismissed = sources.filter { it.status == SourceStatus.DISMISSED },
        counts = BookFilter.entries.associateWith { f -> sources.count { f.matches(it) } },
    )

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
