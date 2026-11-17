// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/*
 * md-lite, the text format of lesson blocks: **bold**, *italic*, `code`, lists with "- " or "1. "
 * at the start of a line, and a blank line between paragraphs. Nothing else. A marker without a
 * partner is shown as typed.
 */

/** A paragraph or a list, in the order they appear. */
sealed interface MdBlock {
    data class Paragraph(
        val text: AnnotatedString,
    ) : MdBlock

    data class ListBlock(
        val ordered: Boolean,
        val items: List<MdListItem>,
    ) : MdBlock
}

/** [marker] is "•" for a bullet, or the number as written ("3.") for an ordered item. */
data class MdListItem(
    val marker: String,
    val text: AnnotatedString,
)

/** How inline code looks; the caller passes theme colors. */
data class MdStyles(
    val code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
)

private val BULLET = Regex("""^\s*- (.*\S.*)$""")
private val NUMBERED = Regex("""^\s*(\d{1,3})\. (.*\S.*)$""")

fun parseMdLite(
    text: String,
    styles: MdStyles = MdStyles(),
): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()
    var listOrdered = false
    val listItems = mutableListOf<Pair<String, StringBuilder>>()

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        blocks.add(MdBlock.Paragraph(mdInline(paragraph.joinToString("\n"), styles)))
        paragraph.clear()
    }

    fun flushList() {
        if (listItems.isEmpty()) return
        blocks.add(
            MdBlock.ListBlock(
                listOrdered,
                listItems.map { (marker, item) ->
                    MdListItem(marker, mdInline(item.toString(), styles))
                },
            ),
        )
        listItems.clear()
    }

    fun startItem(
        ordered: Boolean,
        marker: String,
        content: String,
    ) {
        flushParagraph()
        if (listItems.isNotEmpty() && listOrdered != ordered) flushList()
        listOrdered = ordered
        listItems.add(marker to StringBuilder(content.trim()))
    }

    for (line in text.replace("\r\n", "\n").split('\n')) {
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            line.isBlank() -> {
                flushParagraph()
                flushList()
            }
            bullet != null -> startItem(ordered = false, marker = "•", content = bullet.groupValues[1])
            numbered != null -> startItem(ordered = true, marker = "${numbered.groupValues[1]}.", content = numbered.groupValues[2])
            // a line right below a list item continues that item
            listItems.isNotEmpty() ->
                listItems
                    .last()
                    .second
                    .append(' ')
                    .append(line.trim())
            else -> paragraph.add(line.trim())
        }
    }
    flushParagraph()
    flushList()
    return blocks
}

/** Inline md-lite of one paragraph or list item. */
fun mdInline(
    text: String,
    styles: MdStyles = MdStyles(),
): AnnotatedString = buildAnnotatedString { appendInline(text, styles) }

private fun AnnotatedString.Builder.appendInline(
    text: String,
    styles: MdStyles,
) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(styles.code) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        } else if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(text.substring(i + 2, end), styles) }
                i = end + 2
                continue
            }
        } else if (c == '*') {
            val end = italicEnd(text, i)
            if (end > 0) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), styles) }
                i = end + 1
                continue
            }
        }
        append(c)
        i++
    }
}

/**
 * The closing `*` of an italic span opened at [start], or -1. Like Markdown, the text must hug the
 * markers, so "2 * 3 * 4" stays as typed.
 */
private fun italicEnd(
    text: String,
    start: Int,
): Int {
    val first = text.getOrNull(start + 1) ?: return -1
    if (first.isWhitespace() || first == '*') return -1
    var j = start + 2
    while (j < text.length) {
        if (text[j] == '*' && !text[j - 1].isWhitespace() && text.getOrNull(j + 1) != '*' && text[j - 1] != '*') return j
        j++
    }
    return -1
}
