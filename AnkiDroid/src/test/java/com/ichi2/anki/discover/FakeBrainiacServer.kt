// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * A fake Brainiac service: answers requests in-process, so [OkHttpDiscoverApi] runs its full
 * request/response path (URL, headers, body, status codes) without a socket.
 */
class FakeBrainiacServer {
    data class Recorded(
        val method: String,
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )

    data class Reply(
        val code: Int,
        val body: String,
    )

    private val handlers = mutableListOf<Pair<(Recorded) -> Boolean, () -> Reply>>()
    val requests = mutableListOf<Recorded>()

    /** When set, every call fails like an unreachable server. */
    var offline = false

    fun on(
        method: String,
        path: String,
        reply: () -> Reply,
    ) {
        handlers.add(0, { r: Recorded -> r.method == method && r.path == path } to reply)
    }

    fun on(
        method: String,
        path: String,
        code: Int,
        body: String,
    ) = on(method, path) { Reply(code, body) }

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(
                Interceptor { chain ->
                    if (offline) throw IOException("server unreachable")
                    val recorded = record(chain.request())
                    requests.add(recorded)
                    val reply =
                        handlers.firstOrNull { it.first(recorded) }?.second?.invoke()
                            ?: Reply(404, """{"detail": "no handler for ${recorded.method} ${recorded.path}"}""")
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(reply.code)
                        .message("fake")
                        .body(reply.body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            ).build()

    private fun record(request: Request): Recorded {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return Recorded(
            method = request.method,
            path = request.url.encodedPath,
            query = request.url.encodedQuery,
            headers = request.headers.associate { (name, value) -> name.lowercase() to value },
            body = buffer.readUtf8(),
        )
    }
}
