// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ichi2.anki.R
import com.ichi2.anki.discover.CalloutBlock
import com.ichi2.anki.discover.CalloutKind
import com.ichi2.anki.discover.CodeBlock
import com.ichi2.anki.discover.CompareBlock
import com.ichi2.anki.discover.CompareSide
import com.ichi2.anki.discover.ExampleBlock
import com.ichi2.anki.discover.FlowBlock
import com.ichi2.anki.discover.FlowNode
import com.ichi2.anki.discover.LessonBlock
import com.ichi2.anki.discover.LessonScreen
import com.ichi2.anki.discover.TableBlock
import com.ichi2.anki.discover.TextBlock
import com.ichi2.anki.ui.compose.theme.AnkiDroidTheme

/** Body text of a lesson: sized and spaced for reading, not for a list. */
@Composable
internal fun readingStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)

/** Title of a block: as large as the body text, but bold. */
@Composable
internal fun blockTitleStyle(): TextStyle = readingStyle().copy(fontWeight = FontWeight.Bold)

/** Smaller text inside boxes (notes, table cells, compare points). */
@Composable
internal fun compactReadingStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)

private val BLOCK_SHAPE = RoundedCornerShape(16.dp)

/** One screen of a lesson: its heading and blocks. */
@Composable
internal fun LessonScreenContent(
    screen: LessonScreen,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        screen.heading?.let { Text(it, style = MaterialTheme.typography.headlineSmall) }
        screen.displayBlocks.forEach { LessonBlockView(it) }
    }
}

@Composable
internal fun LessonBlockView(block: LessonBlock) {
    when (block) {
        is TextBlock -> MdText(block.body)
        is CalloutBlock -> CalloutView(block)
        is ExampleBlock -> ExampleView(block)
        is CompareBlock -> CompareView(block)
        is TableBlock -> TableView(block)
        is FlowBlock -> FlowView(block)
        is CodeBlock -> CodeView(block)
    }
}

/** md-lite text: paragraphs and lists. */
@Composable
internal fun MdText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = readingStyle(),
    color: Color = Color.Unspecified,
) {
    val codeStyle =
        SpanStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = style.fontSize * 0.88f,
            // a tint of the text color, so code stays readable on every container color
            background = (if (color != Color.Unspecified) color else LocalContentColor.current).copy(alpha = 0.12f),
        )
    val blocks = remember(text, codeStyle) { parseMdLite(text, MdStyles(codeStyle)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(block.text, style = style, color = color)
                is MdBlock.ListBlock ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        block.items.forEach { item ->
                            Row {
                                Text(item.marker, style = style, color = color, modifier = Modifier.widthIn(min = 24.dp))
                                Text(item.text, style = style, color = color, modifier = Modifier.weight(1f))
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun BlockLabel(
    text: String,
    color: Color,
) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
}

@Composable
private fun CalloutView(block: CalloutBlock) {
    val colors = MaterialTheme.colorScheme
    val (container, content, accent, label) =
        when (block.kind) {
            CalloutKind.KEY ->
                CalloutColors(
                    colors.primaryContainer,
                    colors.onPrimaryContainer,
                    colors.primary,
                    R.string.discover_callout_key,
                )
            CalloutKind.PITFALL ->
                CalloutColors(
                    colors.errorContainer,
                    colors.onErrorContainer,
                    colors.error,
                    R.string.discover_callout_pitfall,
                )
            CalloutKind.NUANCE ->
                CalloutColors(
                    colors.tertiaryContainer,
                    colors.onTertiaryContainer,
                    colors.tertiary,
                    R.string.discover_callout_nuance,
                )
            CalloutKind.PRACTICE ->
                CalloutColors(colors.secondaryContainer, colors.onSecondaryContainer, colors.secondary, R.string.discover_callout_practice)
        }
    Surface(shape = BLOCK_SHAPE, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BlockLabel(stringResource(label), content)
                block.title?.let { Text(it, style = blockTitleStyle()) }
                MdText(block.body, color = content)
            }
        }
    }
}

private data class CalloutColors(
    val container: Color,
    val content: Color,
    val accent: Color,
    val label: Int,
)

@Composable
private fun ExampleView(block: ExampleBlock) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = BLOCK_SHAPE, color = colors.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(block.title, style = blockTitleStyle())
            block.steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.padding(top = 1.dp).size(26.dp).background(colors.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelLarge, color = colors.onPrimary)
                    }
                    MdText(step, modifier = Modifier.weight(1f), style = compactReadingStyle())
                }
            }
            block.result?.let { result ->
                Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BlockLabel(stringResource(R.string.discover_example_result), colors.onSecondaryContainer)
                        MdText(result, style = compactReadingStyle(), color = colors.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/** Two columns side by side; stacked when the screen is too narrow to keep them readable. */
@Composable
private fun CompareView(block: CompareBlock) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= COMPARE_SIDE_BY_SIDE) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompareSideView(block.left, Modifier.weight(1f).fillMaxHeight())
                    CompareSideView(block.right, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CompareSideView(block.left, Modifier.fillMaxWidth())
                    CompareSideView(block.right, Modifier.fillMaxWidth())
                }
            }
        }
        block.verdict?.let { verdict ->
            val colors = MaterialTheme.colorScheme
            Surface(shape = BLOCK_SHAPE, color = colors.secondaryContainer, contentColor = colors.onSecondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockLabel(stringResource(R.string.discover_compare_verdict), colors.onSecondaryContainer)
                    MdText(verdict, color = colors.onSecondaryContainer)
                }
            }
        }
    }
}

/** Below this width each column would get fewer than ~20 characters per line. */
private val COMPARE_SIDE_BY_SIDE = 480.dp

@Composable
private fun CompareSideView(
    side: CompareSide,
    modifier: Modifier,
) {
    Surface(shape = BLOCK_SHAPE, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(side.title, style = blockTitleStyle())
            side.points.forEach { point ->
                Row {
                    Text("•", style = compactReadingStyle(), modifier = Modifier.widthIn(min = 20.dp))
                    MdText(point, modifier = Modifier.weight(1f), style = compactReadingStyle())
                }
            }
        }
    }
}

/** A real grid; scrolls sideways when the columns do not fit, with a fade at the edge as a hint. */
@Composable
private fun TableView(block: TableBlock) {
    val colors = MaterialTheme.colorScheme
    val rows = block.normalizedRows
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widths = tableColumnWidths(block.headers, rows, maxWidth)
        val scroll = rememberScrollState()
        Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
            Box {
                Column(Modifier.horizontalScroll(scroll)) {
                    Row(Modifier.background(colors.surfaceContainerHigh)) {
                        block.headers.forEachIndexed { i, header ->
                            Text(
                                header,
                                style = compactReadingStyle(),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(widths[i]).padding(12.dp),
                            )
                        }
                    }
                    rows.forEachIndexed { index, row ->
                        HorizontalDivider(Modifier.width(widths.fold(0.dp) { a, b -> a + b }), color = colors.outlineVariant)
                        Row(Modifier.background(if (index % 2 == 1) colors.surfaceContainerLow else colors.surface)) {
                            row.forEachIndexed { i, cell ->
                                MdText(cell, modifier = Modifier.width(widths[i]).padding(12.dp), style = compactReadingStyle())
                            }
                        }
                    }
                }
                ScrollFade(visible = scroll.canScrollForward, color = colors.surface, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/**
 * Column widths from the text they hold: wide enough for the longest word, preferably for the
 * longest cell, and stretched to fill the screen when there is room. Measured in characters, which
 * is close enough for 16sp text and avoids measuring every cell.
 */
internal fun tableColumnWidths(
    headers: List<String>,
    rows: List<List<String>>,
    available: Dp,
): List<Dp> {
    val preferred =
        headers.indices.map { column ->
            val cells = (listOf(headers[column]) + rows.map { it.getOrElse(column) { "" } }).map { it.replace("*", "").replace("`", "") }
            val longestCell = cells.maxOf { it.length }
            val longestWord = cells.flatMap { it.split(' ') }.maxOf { it.length }
            val minimum = (CHAR_WIDTH * longestWord + CELL_PADDING).coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
            (CHAR_WIDTH * longestCell + CELL_PADDING).coerceIn(minimum, MAX_COLUMN_WIDTH)
        }
    val total = preferred.fold(0.dp) { a, b -> a + b }
    return if (total < available) preferred.map { it * (available / total) } else preferred
}

private val CHAR_WIDTH = 10.dp
private val CELL_PADDING = 24.dp
private val MIN_COLUMN_WIDTH = 88.dp
private val MAX_COLUMN_WIDTH = 240.dp

/** A gradient over the right edge of sideways-scrolling content that continues off screen. */
@Composable
private fun ScrollFade(
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier
            .width(32.dp)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color))),
    )
}

@Composable
private fun FlowView(block: FlowBlock) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        block.title?.let {
            Text(
                it,
                style = blockTitleStyle(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        block.nodes.forEachIndexed { index, node ->
            if (index > 0) {
                Icon(
                    painterResource(R.drawable.keyboard_arrow_down_24px),
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.padding(vertical = 2.dp).size(28.dp),
                )
            }
            Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(node.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    node.note?.let { MdText(it, style = compactReadingStyle(), color = colors.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun CodeView(block: CodeBlock) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.language?.let {
                Text(
                    it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Box(Modifier.height(IntrinsicSize.Min)) {
                Text(
                    block.body,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(scroll).padding(horizontal = 16.dp),
                )
                ScrollFade(
                    visible = scroll.canScrollForward,
                    color = colors.surfaceContainerHighest,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** Every block type, for previews and for trying the layout. */
internal val sampleBlocks: List<LessonBlock> =
    listOf(
        TextBlock("Een **embedding** is een lijst getallen.\n\n- dichtbij = *verwant*\n- ver weg = los\n\n1. tekst in\n2. `vector` uit"),
        CalloutBlock(CalloutKind.KEY, "Onthoud", "Betekenis wordt **afstand**."),
        CalloutBlock(CalloutKind.PITFALL, null, "Cosinus is geen *kans*."),
        CalloutBlock(CalloutKind.NUANCE, null, "Werkt minder goed voor zeldzame woorden."),
        CalloutBlock(CalloutKind.PRACTICE, null, "Zoek in polisvoorwaarden op betekenis."),
        ExampleBlock("Rekenvoorbeeld", listOf("Neem `[1, 0]` en `[0.8, 0.6]`.", "Inproduct: 0.8."), "Cosinus **0.8**: sterk verwant."),
        CompareBlock(
            CompareSide("Trefwoord", listOf("exacte woorden", "snel")),
            CompareSide("Embedding", listOf("betekenis", "synoniemen")),
            "Combineer beide.",
        ),
        TableBlock(listOf("Model", "Dimensies", "Kosten"), listOf(listOf("klein", "384", "laag"), listOf("groot", "3072", "**hoog**"))),
        FlowBlock(
            "Zoeken",
            listOf(FlowNode("Vraag", "tekst van de gebruiker"), FlowNode("Embedding"), FlowNode("Dichtstbijzijnde", "top 5")),
        ),
        CodeBlock("json", """{"input": "schadelast", "model": "text-embedding-3-small", "dimensions": 1536}"""),
    )

@Preview(name = "Lesson blocks, light", heightDp = 2400)
@Preview(name = "Lesson blocks, dark", heightDp = 2400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LessonBlocksPreview() {
    AnkiDroidTheme {
        Surface {
            LessonScreenContent(
                LessonScreen(heading = "Wat een embedding is", blocks = sampleBlocks),
                Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            )
        }
    }
}
