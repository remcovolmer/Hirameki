// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.NoteId

/** What to add to the collection for an approved proposal. */
data class NoteSpec(
    val deck: String,
    val notetype: String,
    val fields: Map<String, String>,
    val tags: List<String>,
)

sealed interface NoteCreationResult {
    data class Created(
        val noteId: NoteId,
    ) : NoteCreationResult

    /** The collection has no notetype with this name. Nothing was created. */
    data class MissingNotetype(
        val notetype: String,
    ) : NoteCreationResult

    /** The proposal names fields the notetype does not have. Nothing was created. */
    data class UnknownFields(
        val notetype: String,
        val fields: List<String>,
    ) : NoteCreationResult
}

fun interface DiscoverNoteCreator {
    suspend fun create(spec: NoteSpec): NoteCreationResult
}

/** Adds notes to the open collection. */
object CollectionDiscoverNoteCreator : DiscoverNoteCreator {
    override suspend fun create(spec: NoteSpec): NoteCreationResult = withCol { addDiscoverNote(spec) }
}

/** The `les::` tag that links a note to its lesson in the Brainiac service. */
fun lessonTag(slug: String) = "les::$slug"

/**
 * Adds a note for [spec]: the deck is created when missing, the notetype must exist.
 * Fields of the notetype that [spec] does not mention stay empty.
 */
fun Collection.addDiscoverNote(spec: NoteSpec): NoteCreationResult {
    val notetype = notetypes.byName(spec.notetype) ?: return NoteCreationResult.MissingNotetype(spec.notetype)
    val unknown = spec.fields.keys - notetype.fieldsNames.toSet()
    if (unknown.isNotEmpty()) {
        return NoteCreationResult.UnknownFields(spec.notetype, unknown.sorted())
    }
    val deckId = decks.id(spec.deck)
    val note = newNote(notetype)
    for ((name, value) in spec.fields) {
        note.setItem(name, value)
    }
    // Anki separates tags by spaces, so a space inside a tag would split it
    note.tags =
        spec.tags
            .map { it.trim().replace(Regex("\\s+"), "_") }
            .filter { it.isNotEmpty() }
            .distinct()
            .toMutableList()
    addNote(note, deckId)
    return NoteCreationResult.Created(note.id)
}
