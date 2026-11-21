// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertNull

class LessonStacksTest {
    private fun lesson(
        id: Long,
        stack: LessonStackRef? = null,
        read: Boolean = false,
        cardsLeft: Int = 0,
        minutes: Int? = null,
        position: Int? = null,
        sourceId: Long? = null,
        track: String = "boek",
        createdAt: String? = null,
    ) = LessonSummary(
        id = id,
        slug = "l$id",
        track = track,
        title = "Les $id",
        status = if (read) LessonStatus.READ else LessonStatus.NEW,
        readingMinutes = minutes,
        position = position,
        sourceId = sourceId,
        stack = stack,
        createdAt = createdAt,
        proposalCounts = ProposalCounts(new = cardsLeft),
    )

    private val book = LessonStackRef("bron:3", "Atomic Habits", StackKind.BOOK)
    private val path = LessonStackRef("pad:ai", "AI-pad", StackKind.PATH)

    @Test
    fun `progress of a lesson`() {
        assertEquals(LessonProgress.Open, lesson(1, cardsLeft = 2).progress)
        assertEquals(LessonProgress.CardsLeft, lesson(1, read = true, cardsLeft = 2).progress)
        assertEquals(LessonProgress.Done, lesson(1, read = true).progress)
    }

    @Test
    fun `stacks keep the order of the service`() {
        val stacks =
            groupIntoStacks(
                listOf(
                    lesson(5, path),
                    lesson(1, book, sourceId = 3),
                    lesson(6, path),
                    lesson(2, book, sourceId = 3),
                ),
            )

        assertEquals(listOf("pad:ai", "bron:3"), stacks.map { it.key })
        assertEquals(listOf(5L, 6L), stacks[0].lessons.map { it.id })
        assertEquals(listOf(1L, 2L), stacks[1].lessons.map { it.id })
        assertEquals(3L, stacks[1].sourceId)
    }

    @Test
    fun `next lesson is the first that is not done, also when an earlier one has cards left`() {
        val stack =
            groupIntoStacks(
                listOf(
                    lesson(1, book, read = true),
                    lesson(2, book, read = true, cardsLeft = 1),
                    lesson(3, book),
                ),
            ).single()

        assertEquals(2L, stack.nextLesson?.id)
        assertEquals(1, stack.doneCount)
        assertEquals(3L, stack.nextAfter(2)?.id)
        assertNull(stack.nextAfter(3))
        assertEquals(3, stack.numberOf(3))
    }

    @Test
    fun `progress, remaining reading time and cards of a stack`() {
        val stack =
            groupIntoStacks(
                listOf(
                    lesson(1, book, read = true, minutes = 4),
                    lesson(2, book, read = true, cardsLeft = 2, minutes = 5),
                    lesson(3, book, cardsLeft = 3, minutes = 6),
                    lesson(4, book, cardsLeft = 1, minutes = 2),
                ),
            ).single()

        assertEquals(1, stack.doneCount)
        assertEquals(4, stack.total)
        assertEquals("only unread lessons count", 8, stack.remainingMinutes)
        assertEquals(6, stack.cardsToReview)
        assertEquals(false, stack.finished)
    }

    @Test
    fun `no reading times means no remaining time`() {
        assertNull(groupIntoStacks(listOf(lesson(1, book))).single().remainingMinutes)
    }

    @Test
    fun `a finished stack has no next lesson`() {
        val stack = groupIntoStacks(listOf(lesson(1, book, read = true), lesson(2, book, read = true))).single()

        assertEquals(true, stack.finished)
        assertNull(stack.nextLesson)
    }

    @Test
    fun `without stack from the server, stacks are derived and sorted by position`() {
        val stacks =
            groupIntoStacks(
                listOf(
                    lesson(10, position = 2, sourceId = 3, createdAt = "2026-11-18T08:00:00Z"),
                    lesson(11, position = null, sourceId = 3, createdAt = "2026-11-18T07:00:00Z"),
                    lesson(12, position = 1, sourceId = 3, createdAt = "2026-11-18T09:00:00Z"),
                    lesson(20, track = "ai"),
                    lesson(30, track = "los"),
                ),
            )

        assertEquals(listOf("bron:3", "pad:ai", "los"), stacks.map { it.key })
        assertEquals("position first, lessons without one last", listOf(12L, 10L, 11L), stacks[0].lessons.map { it.id })
        assertEquals(StackKind.PATH, stacks[1].kind)
        assertEquals("AI-pad", stacks[1].title)
        assertEquals(StackKind.LOOSE, stacks[2].kind)
    }

    @Test
    fun `when the server sends stacks, the app does not reorder them`() {
        val stack =
            groupIntoStacks(
                listOf(lesson(2, book, position = 2), lesson(1, book, position = 1)),
            ).single()

        assertEquals(listOf(2L, 1L), stack.lessons.map { it.id })
    }

    @Test
    fun `a read lesson whose cards are all decided or withdrawn is done`() {
        val withdrawnOnly =
            discoverJson.decodeFromString(
                LessonSummary.serializer(),
                """{"id": 1, "slug": "a", "title": "t", "status": "gelezen",
                    "proposal_counts": {"nieuw": 0, "goedgekeurd": 3, "afgewezen": 5, "feedback": 0, "ingetrokken": 2, "toekomstig": 1}}""",
            )

        assertEquals(LessonProgress.Done, withdrawnOnly.progress)
        assertEquals(2, withdrawnOnly.proposalCounts.withdrawn)
    }
}
