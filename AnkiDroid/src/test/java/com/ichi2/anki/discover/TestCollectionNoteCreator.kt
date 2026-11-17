// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.NoteId

/**
 * [DiscoverNoteCreator] on a test collection, without `withCol`.
 * With [dieAfterCreate] it throws right after adding the note, like an app death before the note id
 * is stored.
 */
class TestCollectionNoteCreator(
    private val col: () -> Collection,
) : DiscoverNoteCreator {
    var dieAfterCreate = false

    override suspend fun create(spec: NoteSpec): NoteCreationResult {
        val result = col().addDiscoverNote(spec)
        if (dieAfterCreate && result is NoteCreationResult.Created) throw SimulatedAppDeath()
        return result
    }

    override suspend fun find(spec: NoteSpec): NoteId? = col().findDiscoverNote(spec)
}

class SimulatedAppDeath : RuntimeException("simulated process death")
