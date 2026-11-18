// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

const val BASIC_NOTETYPE = "Basic"
const val APPLY_NOTETYPE = "Brainiac Toepassing"
private const val APPLY_SCENARIOS = 4
private const val APPLY_CORE_IDEA = "Kernidee"

/**
 * How the fields of a proposal are shown for review: each group is one card, in reading order.
 *
 * - `Brainiac Toepassing`: every scenario together with its answer (empty pairs left out), then
 *   any field the app does not expect, then `Kernidee`.
 * - Any other notetype: one card per field, in the order the service sent them.
 */
fun reviewFieldGroups(
    notetype: String,
    fields: Map<String, String>,
): List<List<Pair<String, String>>> {
    if (notetype != APPLY_NOTETYPE) return fields.map { listOf(it.toPair()) }
    val pairNames = (1..APPLY_SCENARIOS).map { "Scenario$it" to "Antwoord$it" }
    val pairs =
        pairNames.mapNotNull { (scenario, answer) ->
            val s = fields[scenario].orEmpty()
            val a = fields[answer].orEmpty()
            if (s.isBlank() && a.isBlank()) null else listOf(scenario to s, answer to a)
        }
    val known = pairNames.flatMap { listOf(it.first, it.second) }.toSet() + APPLY_CORE_IDEA
    val others = fields.filterKeys { it !in known }.map { listOf(it.toPair()) }
    val coreIdea = fields[APPLY_CORE_IDEA]?.let { listOf(listOf(APPLY_CORE_IDEA to it)) }.orEmpty()
    return pairs + others + coreIdea
}
