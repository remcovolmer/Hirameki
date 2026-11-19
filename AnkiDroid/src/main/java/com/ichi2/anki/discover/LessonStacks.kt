// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

/*
 * Lessons come in stacks: one per book, one per learning path, and one for loose lessons. The
 * service sends them in stack order; the app walks a stack from its first open lesson to its last.
 */

/** Where a lesson stands for the user. */
enum class LessonProgress {
    /** Not read yet. */
    Open,

    /** Read, but cards are still waiting for a decision. */
    CardsLeft,

    /** Read and every card decided. */
    Done,
}

val LessonSummary.progress: LessonProgress
    get() =
        when {
            status != LessonStatus.READ -> LessonProgress.Open
            proposalCounts.new > 0 -> LessonProgress.CardsLeft
            else -> LessonProgress.Done
        }

data class LessonStack(
    val key: String,
    val title: String,
    val kind: StackKind,
    /** The book behind a book stack, for its cover. */
    val sourceId: Long?,
    /** In the order to read them. */
    val lessons: List<LessonSummary>,
) {
    /** A book or paper: a stack with a source, and so a cover or placeholder. */
    val hasSource: Boolean get() = kind == StackKind.BOOK || kind == StackKind.PAPER

    val total: Int get() = lessons.size
    val doneCount: Int get() = lessons.count { it.progress == LessonProgress.Done }
    val finished: Boolean get() = lessons.all { it.progress == LessonProgress.Done }
    val cardsToReview: Int get() = lessons.sumOf { it.proposalCounts.new }

    /** Reading time of the lessons not yet read; null when the service sends no reading times. */
    val remainingMinutes: Int?
        get() {
            val unread = lessons.filter { it.progress == LessonProgress.Open }
            if (unread.none { it.readingMinutes != null }) return null
            return unread.sumOf { it.readingMinutes ?: 0 }
        }

    /** The first lesson that is not done: the one "Continue" opens. */
    val nextLesson: LessonSummary? get() = lessons.firstOrNull { it.progress != LessonProgress.Done }

    /** The first lesson after [lessonId] that is not done, or null at the end of the stack. */
    fun nextAfter(lessonId: Long): LessonSummary? {
        val index = lessons.indexOfFirst { it.id == lessonId }
        if (index < 0) return null
        return lessons.drop(index + 1).firstOrNull { it.progress != LessonProgress.Done }
    }

    /** 1-based place of [lessonId] in the stack. */
    fun numberOf(lessonId: Long): Int = lessons.indexOfFirst { it.id == lessonId } + 1
}

/** The stack of a lesson; derived from its fields for servers that do not send `stack`. */
fun stackOf(lesson: LessonSummary): LessonStackRef =
    lesson.stack ?: when {
        lesson.sourceId != null -> LessonStackRef("bron:${lesson.sourceId}", lesson.sourceTitle ?: lesson.title, StackKind.BOOK)
        lesson.track == "ai" -> LessonStackRef("pad:ai", "AI-pad", StackKind.PATH)
        lesson.track == "verz" -> LessonStackRef("pad:verz", "Verzekeringspad", StackKind.PATH)
        else -> LessonStackRef("los", "Losse lessen", StackKind.LOOSE)
    }

/**
 * Groups lessons into stacks. Stacks keep the order in which the service sends them, and so do
 * the lessons inside a stack. Only when a server does not send `stack` (older versions) are the
 * lessons of a stack sorted here, by `position` and then creation time.
 */
fun groupIntoStacks(lessons: List<LessonSummary>): List<LessonStack> {
    val serverOrdered = lessons.all { it.stack != null }
    return lessons
        .groupBy { stackOf(it).key }
        .map { (key, group) ->
            val ref = stackOf(group.first())
            val ordered =
                if (serverOrdered) {
                    group
                } else {
                    group.sortedWith(compareBy<LessonSummary, Int?>(nullsLast()) { it.position }.thenBy { it.createdAt.orEmpty() })
                }
            LessonStack(
                key = key,
                title = ref.title,
                kind = ref.kind,
                sourceId = group.firstNotNullOfOrNull { it.sourceId } ?: key.removePrefix("bron:").toLongOrNull(),
                lessons = ordered,
            )
        }
}
