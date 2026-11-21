// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNull

/** Papers as a source: the model, the Books tab filters and the link checks. */
class PapersTest {
    private fun source(json: String) = discoverJson.decodeFromString(Source.serializer(), json)

    @Test
    fun `a paper is read with its link`() {
        val paper =
            source(
                """{"id": 7, "title": "Attention Is All You Need", "author": "Vaswani e.a.", "kind": "paper", "year": 2017,
                    "status": "wachtrij", "url": "https://arxiv.org/abs/1706.03762", "isbn": null, "has_cover": false}""",
            )

        assertTrue(paper.isPaper)
        assertEquals("https://arxiv.org/abs/1706.03762", paper.openableUrl)
        assertEquals("Vaswani e.a. · 2017", paper.paperByline())
    }

    @Test
    fun `unknown source and stack kinds still parse`() {
        val source = source("""{"id": 1, "title": "x", "kind": "podcast"}""")
        val stack = discoverJson.decodeFromString(LessonStackRef.serializer(), """{"key": "bron:1", "title": "x", "kind": "video"}""")

        assertEquals(SourceKind.BOOK, source.kind)
        assertEquals(StackKind.LOOSE, stack.kind)
    }

    @Test
    fun `a paper stack has a source, like a book`() {
        val stack =
            discoverJson.decodeFromString(
                LessonStackRef.serializer(),
                """{"key": "bron:7", "title": "Attention", "kind": "paper"}""",
            )
        val lesson = LessonSummary(id = 1, slug = "a", title = "Idee", sourceId = 7, position = 1, stack = stack)

        val grouped = groupIntoStacks(listOf(lesson)).single()

        assertEquals(StackKind.PAPER, grouped.kind)
        assertTrue(grouped.hasSource)
        assertEquals(7L, grouped.sourceId)
    }

    @Test
    fun `only https links are opened or accepted`() {
        assertEquals("https://arxiv.org/abs/1706.03762", httpsUrlOrNull(" https://arxiv.org/abs/1706.03762 "))
        assertNull(httpsUrlOrNull("http://arxiv.org/abs/1706.03762"))
        assertNull(httpsUrlOrNull("javascript:alert(1)"))
        assertNull(httpsUrlOrNull("file:///tmp/x.pdf"))
        assertNull(httpsUrlOrNull("arxiv.org/abs/1706.03762"))
        assertNull(httpsUrlOrNull(""))
        assertNull(httpsUrlOrNull(null))
        assertNull(Source(id = 1, title = "x", url = "http://example.com").openableUrl)
    }

    @Test
    fun `adding a paper needs an https link, a book does not`() {
        assertFalse(canAddSource("Attention", SourceKind.PAPER, ""))
        assertFalse(canAddSource("Attention", SourceKind.PAPER, "http://arxiv.org/abs/1"))
        assertTrue(canAddSource("Attention", SourceKind.PAPER, "https://arxiv.org/abs/1"))
        assertTrue(canAddSource("Atomic Habits", SourceKind.BOOK, ""))
        assertFalse("an optional link must still be valid", canAddSource("Atomic Habits", SourceKind.BOOK, "niet-een-link"))
        assertFalse(canAddSource(" ", SourceKind.BOOK, ""))
    }

    @Test
    fun `the kind filter splits books and papers, status counts follow it`() {
        val sources =
            listOf(
                Source(id = 1, title = "Boek", status = SourceStatus.QUEUED),
                Source(id = 2, title = "Paper", kind = SourceKind.PAPER, status = SourceStatus.QUEUED),
                Source(id = 3, title = "Paper 2", kind = SourceKind.PAPER, status = SourceStatus.PROCESSED),
                Source(id = 4, title = "Paper 3", kind = SourceKind.PAPER, status = SourceStatus.DISMISSED),
            )

        val papers = bookShelf(sources, BookFilter.ToReview, KindFilter.Papers)
        val books = bookShelf(sources, BookFilter.ToReview, KindFilter.Books)
        val all = bookShelf(sources, BookFilter.ToReview, KindFilter.All)

        assertEquals(listOf(2L), papers.groups.flatMap { it.books }.map { it.id })
        assertEquals(1, papers.counts[BookFilter.Processed])
        assertEquals(listOf(4L), papers.dismissed.map { it.id })
        assertEquals(listOf(1L), books.groups.flatMap { it.books }.map { it.id })
        assertEquals(0, books.counts[BookFilter.Processed])
        assertTrue(books.dismissed.isEmpty())
        assertEquals(2, all.counts[BookFilter.ToReview])
        assertTrue(all.hasPapers)
        assertFalse(bookShelf(sources.take(1), BookFilter.ToReview).hasPapers)
    }

    @Test
    fun `a new paper is sent with its link`() {
        val body =
            discoverJson.encodeToString(
                NewSourceRequest.serializer(),
                NewSourceRequest("Attention", kind = SourceKind.PAPER, url = "https://arxiv.org/abs/1"),
            )

        assertTrue(body.contains("\"kind\":\"paper\""))
        assertTrue(body.contains("\"url\":\"https://arxiv.org/abs/1\""))
    }

    @Test
    fun `a withdrawn or replaced proposal is read`() {
        val withdrawn =
            discoverJson.decodeFromString(
                Proposal.serializer(),
                """{"id": 1, "lesson_id": 2, "deck": "d", "notetype": "Basic", "status": "ingetrokken"}""",
            )
        val replaced =
            discoverJson.decodeFromString(
                Proposal.serializer(),
                """{"id": 1, "lesson_id": 2, "deck": "d", "notetype": "Basic", "status": "goedgekeurd", "replaced": true}""",
            )

        assertEquals(ProposalStatus.WITHDRAWN, withdrawn.status)
        assertTrue(replaced.replaced)
    }
}
