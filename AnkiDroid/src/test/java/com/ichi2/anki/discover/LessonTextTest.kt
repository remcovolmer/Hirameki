// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import androidx.compose.ui.text.font.FontWeight
import com.ichi2.anki.discover.ui.boldMarkup
import com.ichi2.anki.discover.ui.lessonParagraphs
import org.junit.Assert.assertEquals
import org.junit.Test

class LessonTextTest {
    @Test
    fun `bold markup becomes bold spans`() {
        val text = boldMarkup("Een **bestaande** gewoonte is een **startsein**.")

        assertEquals("Een bestaande gewoonte is een startsein.", text.text)
        val bold = text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }.map { text.text.substring(it.start, it.end) }
        assertEquals(listOf("bestaande", "startsein"), bold)
    }

    @Test
    fun `an unmatched marker is shown as is`() {
        val text = boldMarkup("a ** b")

        assertEquals("a ** b", text.text)
        assertEquals(0, text.spanStyles.size)
    }

    @Test
    fun `blank lines separate paragraphs`() {
        assertEquals(listOf("Eén.", "Twee\nnog steeds twee.", "Drie."), lessonParagraphs("Eén.\n\nTwee\nnog steeds twee.\r\n  \r\nDrie.\n"))
    }
}
