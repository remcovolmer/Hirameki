// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.ichi2.anki.discover.ui.MdBlock
import com.ichi2.anki.discover.ui.mdInline
import com.ichi2.anki.discover.ui.parseMdLite
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertIs

class MdLiteTest {
    private fun AnnotatedString.styled(predicate: (SpanStyle) -> Boolean): List<String> =
        spanStyles.filter { predicate(it.item) }.map { text.substring(it.start, it.end) }

    private fun AnnotatedString.bold() = styled { it.fontWeight == FontWeight.Bold }

    private fun AnnotatedString.italic() = styled { it.fontStyle == FontStyle.Italic }

    private fun AnnotatedString.code() = styled { it.fontFamily == FontFamily.Monospace }

    @Test
    fun `bold, italic and code`() {
        val text = mdInline("Een **embedding** is *ongeveer* een `vector`.")

        assertEquals("Een embedding is ongeveer een vector.", text.text)
        assertEquals(listOf("embedding"), text.bold())
        assertEquals(listOf("ongeveer"), text.italic())
        assertEquals(listOf("vector"), text.code())
    }

    @Test
    fun `italic and code inside bold`() {
        val text = mdInline("**heel *erg* `snel`**")

        assertEquals("heel erg snel", text.text)
        assertEquals(listOf("heel erg snel"), text.bold())
        assertEquals(listOf("erg"), text.italic())
        assertEquals(listOf("snel"), text.code())
    }

    @Test
    fun `markers inside code are literal`() {
        val text = mdInline("`a * b ** c`")

        assertEquals("a * b ** c", text.text)
        assertEquals(emptyList<String>(), text.bold() + text.italic())
    }

    @Test
    fun `unpaired markers stay as typed`() {
        assertEquals("a ** b", mdInline("a ** b").text)
        assertEquals("een `los teken", mdInline("een `los teken").text)
        assertEquals("*begin", mdInline("*begin").text)
        assertEquals("``", mdInline("``").text)
    }

    @Test
    fun `an asterisk between spaces is not italic`() {
        val text = mdInline("2 * 3 * 4 = 24")

        assertEquals("2 * 3 * 4 = 24", text.text)
        assertEquals(emptyList<String>(), text.italic())
    }

    @Test
    fun `empty bold markers stay literal`() {
        assertEquals("****", mdInline("****").text)
    }

    @Test
    fun `blank lines separate paragraphs, single newlines stay inside one`() {
        val blocks = parseMdLite("Eén.\n\nTwee\nnog twee.\r\n  \r\nDrie.\n")

        assertEquals(listOf("Eén.", "Twee\nnog twee.", "Drie."), blocks.map { assertIs<MdBlock.Paragraph>(it).text.text })
    }

    @Test
    fun `bullet and numbered lists`() {
        val blocks = parseMdLite("Intro:\n- een\n- **twee**\n\n3. drie\n4. vier\n- weer bullet")

        assertEquals(4, blocks.size)
        assertEquals("Intro:", assertIs<MdBlock.Paragraph>(blocks[0]).text.text)
        val bullets = assertIs<MdBlock.ListBlock>(blocks[1])
        assertEquals(false, bullets.ordered)
        assertEquals(listOf("een", "twee"), bullets.items.map { it.text.text })
        assertEquals(listOf("•", "•"), bullets.items.map { it.marker })
        assertEquals(listOf("twee"), bullets.items[1].text.bold())
        val numbered = assertIs<MdBlock.ListBlock>(blocks[2])
        assertEquals(true, numbered.ordered)
        assertEquals(listOf("3.", "4."), numbered.items.map { it.marker })
        assertEquals(listOf("weer bullet"), assertIs<MdBlock.ListBlock>(blocks[3]).items.map { it.text.text })
    }

    @Test
    fun `a line below a list item continues it`() {
        val list = assertIs<MdBlock.ListBlock>(parseMdLite("- eerste regel\n  loopt door\n- tweede").single())

        assertEquals(listOf("eerste regel loopt door", "tweede"), list.items.map { it.text.text })
    }

    @Test
    fun `things that look like lists but are not`() {
        val blocks = parseMdLite("-geen spatie\n2.5 procent\n- \n*nadruk*")

        val paragraph = assertIs<MdBlock.Paragraph>(blocks.single())
        assertEquals("-geen spatie\n2.5 procent\n-\nnadruk", paragraph.text.text)
    }

    @Test
    fun `empty text gives nothing`() {
        assertEquals(emptyList<MdBlock>(), parseMdLite(""))
        assertEquals(emptyList<MdBlock>(), parseMdLite("\n \n"))
    }
}
