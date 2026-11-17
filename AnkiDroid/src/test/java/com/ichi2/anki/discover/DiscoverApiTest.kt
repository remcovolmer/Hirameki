// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiscoverApiTest {
    private val server = FakeBrainiacServer()

    private fun TestScope.api(
        url: String = "http://brainiac.test",
        token: String = "secret-token",
    ) = OkHttpDiscoverApi(
        config = DiscoverConfig.parse(url, token)!!,
        client = server.client,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun body(index: Int = server.requests.lastIndex): JsonObject = Json.parseToJsonElement(server.requests[index].body).jsonObject

    @Test
    fun `open lessons are read with the bearer token`() =
        runTest {
            server.on("GET", "/api/v1/lessons", 200, DiscoverTestData.LESSONS)

            val lessons = api().openLessons()

            val lesson = lessons.single()
            assertEquals(7L, lesson.id)
            assertEquals("habit-stacking", lesson.slug)
            assertEquals("Atomic Habits · idee 2 van 7", lesson.subtitle)
            assertEquals(LessonStatus.NEW, lesson.status)
            assertEquals(2, lesson.proposalCounts.new)
            val request = server.requests.single()
            assertEquals("Bearer secret-token", request.headers["authorization"])
            // default filter of the service is "open", so the app sends none
            assertNull(request.query)
        }

    @Test
    fun `lesson with screens, citations and proposals is parsed`() =
        runTest {
            server.on("GET", "/api/v1/lessons/7", 200, DiscoverTestData.LESSON)

            val lesson = api().lesson(7)

            assertEquals(2, lesson.screens.size)
            assertNull(lesson.screens[1].heading)
            assertEquals("https://jamesclear.com/habit-stacking", lesson.citations.single().url)
            val (basic, apply) = lesson.proposals
            assertEquals(ProposalKind.EXPLAIN, basic.kind)
            assertEquals("Basic", basic.notetype)
            assertEquals("Waarom werkt habit stacking?", basic.fields["Front"])
            assertEquals(listOf("brainiac", "boek::atomic-habits"), basic.tags)
            assertEquals(ProposalStatus.NEW, basic.status)
            assertEquals(ProposalStatus.APPROVED, apply.status)
            assertEquals(99L, apply.ankiNoteId)
        }

    @Test
    fun `a base url with a path prefix and trailing slash is respected`() =
        runTest {
            server.on("POST", "/brainiac/api/v1/lessons/7/read", 200, DiscoverTestData.LESSON)

            api(url = "https://example.test/brainiac/").markRead(7)

            assertEquals("/brainiac/api/v1/lessons/7/read", server.requests.single().path)
        }

    @Test
    fun `approve without edits sends only the note id`() =
        runTest {
            server.on("POST", "/api/v1/proposals/11/decision", 200, DiscoverTestData.proposalJson(11, "goedgekeurd", 1234))

            val proposal = api().decide(11, DecisionRequest.approve(1234, editedFields = null))

            assertEquals(ProposalStatus.APPROVED, proposal.status)
            val body = body()
            assertEquals("ja", body["decision"]!!.jsonPrimitive.content)
            assertEquals(1234L, body["anki_note_id"]!!.jsonPrimitive.long)
            assertFalse("fields must be omitted when not edited", "fields" in body)
            assertFalse("reason" in body)
            assertFalse("comment" in body)
        }

    @Test
    fun `approve with edits sends the fields`() =
        runTest {
            server.on("POST", "/api/v1/proposals/11/decision", 200, DiscoverTestData.proposalJson(11, "goedgekeurd", 1234))

            api().decide(11, DecisionRequest.approve(1234, mapOf("Front" to "nieuw", "Back" to "B")))

            assertEquals("nieuw", body()["fields"]!!.jsonObject["Front"]!!.jsonPrimitive.content)
        }

    @Test
    fun `reject and feedback bodies follow the contract`() =
        runTest {
            server.on("POST", "/api/v1/proposals/11/decision", 200, DiscoverTestData.proposalJson(11, "afgewezen"))

            api().decide(11, DecisionRequest.reject(RejectReason.NOT_INTERESTING, comment = " "))
            assertEquals("nee", body()["decision"]!!.jsonPrimitive.content)
            assertEquals("niet_interessant", body()["reason"]!!.jsonPrimitive.content)
            assertFalse("blank comment is omitted", "comment" in body())

            api().decide(11, DecisionRequest.reject(RejectReason.OTHER, comment = "te lang"))
            assertEquals("anders", body()["reason"]!!.jsonPrimitive.content)
            assertEquals("te lang", body()["comment"]!!.jsonPrimitive.content)

            api().decide(11, DecisionRequest.feedback("korter"))
            assertEquals("feedback", body()["decision"]!!.jsonPrimitive.content)
            assertEquals("korter", body()["comment"]!!.jsonPrimitive.content)
            assertFalse("anki_note_id" in body())
        }

    @Test
    fun `sources, adding a title and processing`() =
        runTest {
            server.on("GET", "/api/v1/sources", 200, DiscoverTestData.SOURCES)
            server.on(
                "POST",
                "/api/v1/sources",
                201,
                """{"id": 5, "title": "Deep Work", "author": "Cal Newport", "kind": "boek", "status": "wachtrij"}""",
            )
            server.on("POST", "/api/v1/sources/3/process", 202, """{"session_url": "https://claude.ai/code/session_x"}""")

            val sources = api().sources()
            assertEquals(listOf(SourceStatus.QUEUED, SourceStatus.PROCESSED), sources.map { it.status })
            assertTrue(sources[0].canProcess)
            assertFalse(sources[1].canProcess)

            val added = api().addSource(NewSourceRequest(title = "Deep Work", author = "Cal Newport", kind = SourceKind.BOOK))
            assertEquals(5L, added.id)
            assertEquals("boek", body()["kind"]!!.jsonPrimitive.content)
            assertFalse("note" in body())

            assertEquals("https://claude.ai/code/session_x", api().process(3).sessionUrl)
        }

    @Test
    fun `401 is unauthorized`() =
        runTest {
            server.on("GET", "/api/v1/lessons", 401, """{"detail": "invalid token"}""")
            assertFailsWith<DiscoverApiException.Unauthorized> { api().openLessons() }
        }

    @Test
    fun `409 carries the detail`() =
        runTest {
            server.on("POST", "/api/v1/sources/3/process", 409, """{"detail": "routine is al gestart"}""")
            val e = assertFailsWith<DiscoverApiException.Conflict> { api().process(3) }
            assertEquals("routine is al gestart", e.detail)
        }

    @Test
    fun `503 is service unavailable`() =
        runTest {
            server.on("POST", "/api/v1/sources/3/process", 503, """{"detail": "routine niet geconfigureerd"}""")
            val e = assertFailsWith<DiscoverApiException.ServiceUnavailable> { api().process(3) }
            assertEquals("routine niet geconfigureerd", e.detail)
        }

    @Test
    fun `404, 422 and 502 are mapped`() =
        runTest {
            server.on("GET", "/api/v1/lessons/1", 404, """{"detail": "les bestaat niet"}""")
            server.on("POST", "/api/v1/proposals/1/decision", 422, """{"detail": "comment verplicht"}""")
            server.on("POST", "/api/v1/sources/1/process", 502, """{"detail": "upstream down"}""")

            assertEquals("les bestaat niet", assertFailsWith<DiscoverApiException.NotFound> { api().lesson(1) }.detail)
            assertEquals(
                "comment verplicht",
                assertFailsWith<DiscoverApiException.InvalidRequest> { api().decide(1, DecisionRequest.feedback("x")) }.detail,
            )
            val upstream = assertFailsWith<DiscoverApiException.Http> { api().process(1) }
            assertEquals(502, upstream.code)
            assertEquals("upstream down", upstream.detail)
        }

    @Test
    fun `an error body that is not json still maps the status`() =
        runTest {
            server.on("GET", "/api/v1/sources", 500, "<html>Internal Server Error</html>")
            val e = assertFailsWith<DiscoverApiException.Http> { api().sources() }
            assertEquals(500, e.code)
            assertNull(e.detail)
        }

    @Test
    fun `an unreachable server is a network error`() =
        runTest {
            server.offline = true
            assertIs<DiscoverApiException.Network>(assertFailsWith<DiscoverApiException> { api().openLessons() })
        }

    @Test
    fun `an invalid response body is reported`() =
        runTest {
            server.on("GET", "/api/v1/lessons", 200, """{"lessons": "not a list"}""")
            assertFailsWith<DiscoverApiException.InvalidResponse> { api().openLessons() }
        }

    @Test
    fun `config parsing`() {
        assertNull(DiscoverConfig.parse("", "token"))
        assertNull(DiscoverConfig.parse("http://brainiac.test", " "))
        assertNull(DiscoverConfig.parse("ftp://brainiac.test", "token"))
        val noScheme = assertNotNull(DiscoverConfig.parse(" brainiac.volmer.home ", " token "))
        assertEquals("http://brainiac.volmer.home/", noScheme.baseUrl.toString())
        assertEquals("token", noScheme.token)
        assertFalse("token must not leak into logs", "token" in noScheme.toString().substringAfter("token="))
    }
}
