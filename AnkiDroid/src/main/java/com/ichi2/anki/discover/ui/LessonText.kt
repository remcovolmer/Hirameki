// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Splits a lesson screen body into paragraphs; the contract uses blank lines as separators. */
fun lessonParagraphs(body: String): List<String> =
    body
        .replace("\r\n", "\n")
        .split(Regex("\n\\s*\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/**
 * Renders `**bold**`, the only markup a lesson body may contain. An unmatched `**` is shown as is.
 */
fun boldMarkup(text: String): AnnotatedString =
    buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val start = text.indexOf("**", index)
            val end = if (start >= 0) text.indexOf("**", start + 2) else -1
            if (start < 0 || end < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, start))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(text.substring(start + 2, end))
            }
            index = end + 2
        }
    }
