// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertNull

class BookShelfTest {
    private fun book(
        id: Long,
        status: SourceStatus = SourceStatus.QUEUED,
        topic: String? = null,
        title: String = "Boek $id",
        summary: String? = null,
    ) = Source(id = id, title = title, status = status, topic = topic, summary = summary)

    @Test
    fun `filters by status, failed books count as to review`() {
        val sources =
            listOf(
                book(1, SourceStatus.QUEUED),
                book(2, SourceStatus.FAILED),
                book(3, SourceStatus.PROCESSING),
                book(4, SourceStatus.PROCESSED),
                book(5, SourceStatus.DISMISSED),
            )

        val shelf = bookShelf(sources, BookFilter.ToReview)

        assertEquals(listOf(1L, 2L), shelf.groups.flatMap { it.books }.map { it.id })
        assertEquals(mapOf(BookFilter.ToReview to 2, BookFilter.Processing to 1, BookFilter.Processed to 1), shelf.counts)
        assertEquals("dismissed books sit apart, whatever the filter", listOf(5L), shelf.dismissed.map { it.id })
        assertEquals(listOf(4L), bookShelf(sources, BookFilter.Processed).groups.flatMap { it.books }.map { it.id })
    }

    @Test
    fun `groups by topic in order of appearance, books without topic last`() {
        val groups =
            groupByTopic(
                listOf(
                    book(1, topic = "onderhandelen"),
                    book(2),
                    book(3, topic = "AI-strategie"),
                    book(4, topic = " Onderhandelen "),
                    book(5, topic = " "),
                ),
            )

        assertEquals(listOf("onderhandelen", "AI-strategie", null), groups.map { it.topic })
        assertEquals(listOf(1L, 4L), groups[0].books.map { it.id })
        assertEquals(listOf(2L, 5L), groups[2].books.map { it.id })
    }

    @Test
    fun `first line of the summary and initials for the placeholder`() {
        assertEquals("De kern.", book(1, summary = "\n  De kern.\nMeer.").summaryFirstLine())
        assertNull(book(1).summaryFirstLine())
        assertEquals("TI", book(1, title = "Thinking in Systems").initials())
        assertEquals("1S", book(1, title = "1984 – Sequel").initials())
        assertEquals("?", book(1, title = "–").initials())
    }

    @Test
    fun `a source without the new fields is read with empty defaults`() {
        val source = discoverJson.decodeFromString(Source.serializer(), """{"id": 1, "title": "Oud", "status": "wachtrij"}""")

        assertNull(source.summary)
        assertEquals(emptyList<String>(), source.keyPoints)
        assertNull(source.topic)
        assertEquals(false, source.hasCover)
        assertEquals(0, source.lessonCount)
        assertEquals(true, source.canDismiss)
    }

    @Test
    fun `a dismissed source can be restored, not processed or dismissed`() {
        val source = discoverJson.decodeFromString(Source.serializer(), """{"id": 1, "title": "x", "status": "afgewezen"}""")

        assertEquals(SourceStatus.DISMISSED, source.status)
        assertEquals(true, source.canRestore)
        assertEquals(false, source.canProcess)
        assertEquals(false, source.canDismiss)
    }
}
