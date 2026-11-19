// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertNull

/** The expected number of lessons of a source, and what it left out. */
class ExpectedLessonsTest {
    private fun source(
        status: SourceStatus = SourceStatus.QUEUED,
        expected: Int? = null,
        lessons: Int = 0,
        omitted: String? = null,
    ) = Source(id = 1, title = "x", status = status, expectedLessons = expected, lessonCount = lessons, omitted = omitted)

    @Test
    fun `fields are read, and absent on older servers`() {
        val source =
            discoverJson.decodeFromString(
                Source.serializer(),
                """{"id": 1, "title": "x", "expected_lessons": 9, "omitted": "Hoofdstuk 7 overlapt met 3."}""",
            )
        val old = discoverJson.decodeFromString(Source.serializer(), """{"id": 1, "title": "x"}""")

        assertEquals(9, source.expectedLessons)
        assertEquals("Hoofdstuk 7 overlapt met 3.", source.omitted)
        assertNull(old.expectedLessons)
        assertNull(old.omitted)
    }

    @Test
    fun `no estimate, nothing to show`() {
        assertNull(source().lessonCountHint())
        assertNull(source(expected = 0).lessonCountHint())
    }

    @Test
    fun `an unprocessed source shows its estimate, one or many`() {
        assertEquals(LessonCount.Expected(1), source(expected = 1).lessonCountHint())
        assertEquals(LessonCount.Expected(9), source(expected = 9).lessonCountHint())
        assertEquals(LessonCount.Expected(3), source(SourceStatus.PROCESSING, expected = 3).lessonCountHint())
        assertEquals(LessonCount.Expected(4), source(SourceStatus.DISMISSED, expected = 4).lessonCountHint())
    }

    @Test
    fun `a processed source shows its real lessons, else the updated estimate`() {
        assertEquals(LessonCount.Actual(7), source(SourceStatus.PROCESSED, expected = 9, lessons = 7).lessonCountHint())
        assertEquals(LessonCount.Actual(12), source(SourceStatus.PROCESSED, expected = 12).lessonCountHint())
        assertNull(source(SourceStatus.PROCESSED).lessonCountHint())
    }

    @Test
    fun `left out shows only for a processed source with text`() {
        assertEquals("Twee ideeën overlappen.", source(SourceStatus.PROCESSED, omitted = " Twee ideeën overlappen. ").leftOut())
        assertNull(source(SourceStatus.PROCESSED, omitted = " ").leftOut())
        assertNull(source(SourceStatus.QUEUED, omitted = "x").leftOut())
        assertNull(source(SourceStatus.PROCESSED).leftOut())
    }

    @Test
    fun `surname of the first author for the paper placeholder`() {
        fun surname(author: String?) = Source(id = 1, title = "x", author = author).firstAuthorSurname()

        assertEquals("Kalai", surname("Adam Tauman Kalai e.a."))
        assertEquals("Weller", surname("Orion Weller e.a."))
        assertEquals("Conn", surname("Charles Conn, Robert McLean"))
        assertEquals("DeepSeek-AI", surname("DeepSeek-AI e.a."))
        assertEquals("Vaswani", surname("Vaswani et al."))
        assertNull(surname(null))
        assertNull(surname(" "))
    }
}
