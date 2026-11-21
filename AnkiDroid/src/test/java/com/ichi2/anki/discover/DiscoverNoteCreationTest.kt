// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.discover.DiscoverTestData.basicProposal
import com.ichi2.testutils.JvmTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Creating approved notes in a real (in-memory) collection. */
@RunWith(AndroidJUnit4::class)
class DiscoverNoteCreationTest : JvmTest() {
    private fun spec(
        deck: String = "Brainiac::AI-ML",
        notetype: String = "Basic",
        fields: Map<String, String> = mapOf("Front" to "Wat is een <b>embedding</b>?", "Back" to "Een vector."),
        tags: List<String> = listOf("brainiac", "les::embeddings"),
    ) = NoteSpec(deck, notetype, fields, tags)

    @Test
    fun `note is created in a new deck with fields and tags`() {
        assertNull(col.decks.idForName("Brainiac::AI-ML"))

        val result = assertIs<NoteCreationResult.Created>(col.addDiscoverNote(spec()))

        val note = col.getNote(result.noteId)
        assertEquals("Wat is een <b>embedding</b>?", note.getItem("Front"))
        assertEquals("Een vector.", note.getItem("Back"))
        assertEquals(setOf("brainiac", "les::embeddings"), note.tags.toSet())
        val deckId = assertNotNull(col.decks.idForName("Brainiac::AI-ML"))
        assertEquals(deckId, note.cards().single().did)
    }

    @Test
    fun `an existing deck is reused`() {
        val deckId = col.decks.id("Brainiac::AI-ML")

        val result = assertIs<NoteCreationResult.Created>(col.addDiscoverNote(spec()))

        assertEquals(
            deckId,
            col
                .getNote(result.noteId)
                .cards()
                .single()
                .did,
        )
    }

    @Test
    fun `missing notetype creates nothing`() {
        val result = col.addDiscoverNote(spec(notetype = "Brainiac Toepassing", fields = mapOf("Scenario1" to "a")))

        assertEquals(NoteCreationResult.MissingNotetype("Brainiac Toepassing"), result)
        assertEquals(0, col.noteCount())
        assertNull(col.decks.idForName("Brainiac::AI-ML"), "no deck either")
    }

    @Test
    fun `fields the notetype does not have create nothing`() {
        val result = col.addDiscoverNote(spec(fields = mapOf("Front" to "a", "Extra" to "b")))

        assertEquals(NoteCreationResult.UnknownFields("Basic", listOf("Extra")), result)
        assertEquals(0, col.noteCount())
    }

    @Test
    fun `tags with spaces are kept as one tag`() {
        val result = assertIs<NoteCreationResult.Created>(col.addDiscoverNote(spec(tags = listOf("boek::atomic habits", " ", "les::x"))))

        assertEquals(setOf("boek::atomic_habits", "les::x"), col.getNote(result.noteId).tags.toSet())
    }

    @Test
    fun `a yes that could not be reported does not create a second note on retry`() =
        runTest {
            val api = FakeDiscoverApi()
            val decisions =
                ProposalDecisions(api, TestCollectionNoteCreator { col }, InMemoryPendingApprovalStore())
            val proposal = basicProposal()

            api.failWith = DiscoverApiException.Network(IOException("offline"))
            assertIs<DecisionOutcome.SavedLocally>(decisions.approve("embeddings", proposal))
            api.failWith = null
            assertIs<DecisionOutcome.Decided>(decisions.approve("embeddings", proposal))

            assertEquals(1, col.noteCount())
            val noteId = col.findNotes("tag:les::embeddings").single()
            assertEquals(
                noteId,
                api.decisions
                    .single()
                    .second.ankiNoteId,
            )
        }

    @Test
    fun `find returns the note with the same lesson tag, notetype and fields`() {
        val created = assertIs<NoteCreationResult.Created>(col.addDiscoverNote(spec()))

        assertEquals(created.noteId, col.findDiscoverNote(spec()))
        assertNull(col.findDiscoverNote(spec(fields = mapOf("Front" to "anders", "Back" to "Een vector."))))
        assertNull(col.findDiscoverNote(spec(tags = listOf("brainiac", "les::andere-les"))))
        assertNull(col.findDiscoverNote(spec(notetype = "Brainiac Toepassing")))
    }

    @Test
    fun `an app death right after adding the note does not lead to a second note`() =
        runTest {
            val api = FakeDiscoverApi()
            val creator = TestCollectionNoteCreator { col }
            val decisions = ProposalDecisions(api, creator, InMemoryPendingApprovalStore())

            creator.dieAfterCreate = true
            assertFailsWith<SimulatedAppDeath> { decisions.approve("embeddings", basicProposal()) }
            assertEquals(1, col.noteCount())

            creator.dieAfterCreate = false
            assertIs<DecisionOutcome.Decided>(decisions.approve("embeddings", basicProposal()))

            assertEquals(1, col.noteCount())
            assertEquals(
                col.findNotes("tag:les::embeddings").single(),
                api.decisions
                    .single()
                    .second.ankiNoteId,
            )
        }

    @Test
    fun `a scenario deck with an apostrophe and spaces is created as a nested deck`() {
        val deck = "Brainiac Scenario's::Boeken::Atomic Habits"

        val result = assertIs<NoteCreationResult.Created>(col.addDiscoverNote(spec(deck = deck)))

        val deckId = assertNotNull(col.decks.idForName(deck))
        assertEquals(
            deckId,
            col
                .getNote(result.noteId)
                .cards()
                .single()
                .did,
        )
        assertNotNull(col.decks.idForName("Brainiac Scenario's::Boeken"))
        assertEquals(deck, col.decks.name(deckId))
    }
}
