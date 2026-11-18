// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import org.junit.Assert.assertEquals
import org.junit.Test

class ProposalFieldsTest {
    @Test
    fun `apply cards show each scenario with its answer, empty pairs left out, core idea last`() {
        // in the order a server might send them: not grouped
        val fields =
            linkedMapOf(
                "Kernidee" to "kern",
                "Scenario1" to "s1",
                "Scenario2" to "s2",
                "Scenario3" to "",
                "Scenario4" to "s4",
                "Antwoord1" to "a1",
                "Antwoord2" to "a2",
                "Antwoord3" to "",
                "Antwoord4" to "a4",
            )

        assertEquals(
            listOf(
                listOf("Scenario1" to "s1", "Antwoord1" to "a1"),
                listOf("Scenario2" to "s2", "Antwoord2" to "a2"),
                listOf("Scenario4" to "s4", "Antwoord4" to "a4"),
                listOf("Kernidee" to "kern"),
            ),
            reviewFieldGroups(APPLY_NOTETYPE, fields),
        )
    }

    @Test
    fun `a half-filled pair is shown, and unexpected fields come before the core idea`() {
        val fields = linkedMapOf("Kernidee" to "k", "Scenario1" to "s1", "Antwoord1" to "", "Extra" to "x")

        assertEquals(
            listOf(listOf("Scenario1" to "s1", "Antwoord1" to ""), listOf("Extra" to "x"), listOf("Kernidee" to "k")),
            reviewFieldGroups(APPLY_NOTETYPE, fields),
        )
    }

    @Test
    fun `other notetypes keep one card per field in the order received`() {
        val fields = linkedMapOf("Text" to "t", "Extra" to "e")

        assertEquals(listOf(listOf("Text" to "t"), listOf("Extra" to "e")), reviewFieldGroups("Cloze", fields))
    }
}
