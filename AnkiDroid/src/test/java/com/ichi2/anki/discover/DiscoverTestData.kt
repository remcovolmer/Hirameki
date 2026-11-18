// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

/** JSON as the service sends it, following CONTRACT.md. */
object DiscoverTestData {
    const val LESSONS =
        """
        {"lessons": [{
          "id": 7, "slug": "habit-stacking", "source_id": 3, "track": "boek",
          "title": "Habit stacking", "subtitle": "Atomic Habits · idee 2 van 7",
          "status": "nieuw", "created_at": "2026-11-17T07:01:00Z", "read_at": null,
          "proposal_counts": {"nieuw": 2, "goedgekeurd": 0, "afgewezen": 0, "feedback": 0},
          "some_future_field": true
        }]}
        """

    const val LESSON =
        """
        {
          "id": 7, "slug": "habit-stacking", "source_id": 3, "track": "boek",
          "title": "Habit stacking", "subtitle": "Atomic Habits · idee 2 van 7",
          "status": "nieuw", "created_at": "2026-11-17T07:01:00Z", "read_at": null,
          "screens": [
            {"heading": "Koppel een nieuwe gewoonte aan een bestaande", "body": "Een **bestaande** gewoonte.\n\nTweede alinea."},
            {"heading": null, "body": "Het werkt."}
          ],
          "citations": [{"title": "James Clear", "url": "https://jamesclear.com/habit-stacking", "checked": "2026-11-17"}],
          "proposals": [
            {"id": 11, "lesson_id": 7, "kind": "uitleg", "deck": "Brainiac::Boeken", "notetype": "Basic",
             "fields": {"Front": "Waarom werkt habit stacking?", "Back": "Het startsein is een bestaande gewoonte."},
             "tags": ["brainiac", "boek::atomic-habits"], "step": null, "status": "nieuw", "revision": 1,
             "reject_reason": null, "comment": null, "edited": false, "final_fields": null, "anki_note_id": null,
             "created_at": "2026-11-17T07:01:00Z", "decided_at": null},
            {"id": 12, "lesson_id": 7, "kind": "toepas", "deck": "Brainiac::Boeken", "notetype": "Brainiac Toepassing",
             "fields": {"Scenario1": "a", "Scenario2": "b", "Scenario3": "c", "Scenario4": "d", "Antwoord": "e", "Kernidee": "f"},
             "tags": [], "step": null, "status": "goedgekeurd", "revision": 1,
             "reject_reason": null, "comment": null, "edited": false, "final_fields": null, "anki_note_id": 99,
             "created_at": "2026-11-17T07:01:00Z", "decided_at": "2026-11-17T08:00:00Z"}
          ]
        }
        """

    const val ALL_BLOCKS_SCREEN =
        """
        {"heading": "Alle blokken", "body": "platte tekst", "blocks": [
          {"type": "text", "body": "Een **embedding** is een *lijst* getallen."},
          {"type": "callout", "kind": "valkuil", "title": "Let op", "body": "Cosinus is geen kans."},
          {"type": "example", "title": "Rekenvoorbeeld", "steps": ["stap 1", "stap 2"], "result": "uitkomst"},
          {"type": "compare", "left": {"title": "Trefwoord", "points": ["exact"]},
           "right": {"title": "Embedding", "points": ["betekenis"]}, "verdict": "combineer"},
          {"type": "table", "headers": ["Model", "Dimensies"], "rows": [["klein", "384"], ["groot", "3072"]]},
          {"type": "flow", "nodes": [{"label": "Vraag", "note": "tekst"}, {"label": "Embedding"}]},
          {"type": "code", "language": "json", "body": "{\"a\": 1}"}
        ]}
        """

    const val SOURCES =
        """
        {"sources": [
          {"id": 3, "title": "Thinking in Systems", "author": "Donella Meadows", "kind": "boek", "status": "wachtrij",
           "note": null, "created_at": "2026-11-17T07:01:00Z", "processed_at": null, "last_fired_at": null},
          {"id": 4, "title": "Atomic Habits", "author": null, "kind": "boek", "status": "verwerkt",
           "note": null, "created_at": "2026-11-16T07:01:00Z", "processed_at": "2026-11-16T08:01:00Z", "last_fired_at": null}
        ]}
        """

    fun proposalJson(
        id: Long,
        status: String,
        noteId: Long? = null,
    ) = """{"id": $id, "lesson_id": 7, "kind": "uitleg", "deck": "Brainiac::Boeken", "notetype": "Basic",
        "fields": {"Front": "F", "Back": "B"}, "tags": [], "status": "$status", "revision": 1,
        "anki_note_id": ${noteId ?: "null"}}"""

    fun basicProposal(
        id: Long = 11,
        deck: String = "Brainiac::AI-ML",
        notetype: String = "Basic",
        fields: Map<String, String> = mapOf("Front" to "Wat is een embedding?", "Back" to "Een vector."),
        tags: List<String> = listOf("brainiac"),
    ) = Proposal(
        id = id,
        lessonId = 7,
        kind = ProposalKind.EXPLAIN,
        deck = deck,
        notetype = notetype,
        fields = fields,
        tags = tags,
        status = ProposalStatus.NEW,
    )
}
