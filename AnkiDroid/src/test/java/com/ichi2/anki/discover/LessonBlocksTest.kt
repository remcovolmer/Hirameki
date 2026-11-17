// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import androidx.compose.ui.unit.dp
import com.ichi2.anki.discover.ui.tableColumnWidths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Reading lesson content v2 (blocks) and the v1 fallback. */
class LessonBlocksTest {
    private fun screen(json: String): LessonScreen = discoverJson.decodeFromString(LessonScreen.serializer(), json)

    @Test
    fun `every block type is read`() {
        val screen = screen(DiscoverTestData.ALL_BLOCKS_SCREEN)

        assertEquals("Alle blokken", screen.heading)
        val blocks = screen.blocks
        assertEquals(
            listOf(
                TextBlock::class,
                CalloutBlock::class,
                ExampleBlock::class,
                CompareBlock::class,
                TableBlock::class,
                FlowBlock::class,
                CodeBlock::class,
            ),
            blocks.map { it::class },
        )
        assertEquals("Een **embedding** is een *lijst* getallen.", assertIs<TextBlock>(blocks[0]).body)

        val callout = assertIs<CalloutBlock>(blocks[1])
        assertEquals(CalloutKind.PITFALL, callout.kind)
        assertEquals("Let op", callout.title)

        val example = assertIs<ExampleBlock>(blocks[2])
        assertEquals("Rekenvoorbeeld", example.title)
        assertEquals(listOf("stap 1", "stap 2"), example.steps)
        assertEquals("uitkomst", example.result)

        val compare = assertIs<CompareBlock>(blocks[3])
        assertEquals("Trefwoord", compare.left.title)
        assertEquals(listOf("betekenis"), compare.right.points)
        assertEquals("combineer", compare.verdict)

        val table = assertIs<TableBlock>(blocks[4])
        assertEquals(listOf("Model", "Dimensies"), table.headers)
        assertEquals(listOf(listOf("klein", "384"), listOf("groot", "3072")), table.rows)

        val flow = assertIs<FlowBlock>(blocks[5])
        assertEquals(listOf("Vraag", "Embedding"), flow.nodes.map { it.label })
        assertEquals(listOf("tekst", null), flow.nodes.map { it.note })

        val code = assertIs<CodeBlock>(blocks[6])
        assertEquals("json", code.language)
        assertEquals("""{"a": 1}""", code.body)
    }

    @Test
    fun `callout kinds`() {
        val kinds =
            listOf("kern", "valkuil", "nuance", "praktijk").map {
                assertIs<CalloutBlock>(screen("""{"blocks": [{"type": "callout", "kind": "$it", "body": "x"}]}""").blocks.single()).kind
            }

        assertEquals(listOf(CalloutKind.KEY, CalloutKind.PITFALL, CalloutKind.NUANCE, CalloutKind.PRACTICE), kinds)
    }

    @Test
    fun `an unknown block type is skipped, the rest stays`() {
        val screen =
            screen(
                """{"heading": "x", "blocks": [
                    {"type": "video", "url": "https://example.com"},
                    {"type": "text", "body": "blijft"},
                    {"body": "geen type"},
                    "geen object"
                ]}""",
            )

        assertEquals(listOf(TextBlock("blijft")), screen.blocks)
    }

    @Test
    fun `a block that does not match its type is skipped`() {
        val screen = screen("""{"blocks": [{"type": "table", "rows": [["a"]]}, {"type": "code", "body": "ok"}]}""")

        assertEquals(listOf(CodeBlock(null, "ok")), screen.blocks)
    }

    @Test
    fun `a v1 screen with only a body becomes one text block`() {
        val screen = screen("""{"heading": "Oud", "body": "Een **bestaande** gewoonte."}""")

        assertEquals(emptyList<LessonBlock>(), screen.blocks)
        assertEquals(listOf(TextBlock("Een **bestaande** gewoonte.")), screen.displayBlocks)
    }

    @Test
    fun `blocks win over the plain-text body a v2 server also sends`() {
        val screen = screen("""{"blocks": [{"type": "text", "body": "rijk"}], "body": "plat"}""")

        assertEquals(listOf(TextBlock("rijk")), screen.displayBlocks)
    }

    @Test
    fun `table rows are normalized to the number of headers`() {
        val table = TableBlock(listOf("a", "b", "c"), listOf(listOf("1"), listOf("1", "2", "3", "4")))

        assertEquals(listOf(listOf("1", "", ""), listOf("1", "2", "3")), table.normalizedRows)
    }

    @Test
    fun `blocks survive a round trip`() {
        val original = discoverJson.decodeFromString(LessonScreen.serializer(), DiscoverTestData.ALL_BLOCKS_SCREEN)

        val again =
            discoverJson.decodeFromString(
                LessonScreen.serializer(),
                discoverJson.encodeToString(LessonScreen.serializer(), original),
            )

        assertEquals(original.blocks, again.blocks)
    }

    @Test
    fun `reading minutes are read from summary and lesson`() {
        val summary =
            discoverJson.decodeFromString(
                LessonSummary.serializer(),
                """{"id": 1, "slug": "s", "title": "t", "reading_minutes": 4}""",
            )
        val old = discoverJson.decodeFromString(LessonSummary.serializer(), """{"id": 1, "slug": "s", "title": "t"}""")

        assertEquals(4, summary.readingMinutes)
        assertNull(old.readingMinutes)
    }

    @Test
    fun `table columns fit their longest word and fill a wide screen`() {
        val headers = listOf("Model", "Dimensies")
        val rows = listOf(listOf("text-embedding-3-small", "1536"))

        val narrow = tableColumnWidths(headers, rows, 200.dp)
        val wide = tableColumnWidths(headers, rows, 1000.dp)

        assertTrue("the long model name gets the wider column", narrow[0] > narrow[1])
        assertTrue(narrow.fold(0.dp) { a, b -> a + b } > 200.dp)
        assertEquals(1000f, wide.fold(0.dp) { a, b -> a + b }.value, 0.5f)
    }
}
