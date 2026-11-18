// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/**
 * GitHub in-process: answers by `host + path`, so the updater's own redirect handling, host checks
 * and Range headers run as against the real hosts, without a socket.
 */
class FakeGitHub {
    data class Recorded(
        val url: String,
        val headers: Map<String, String>,
    )

    class Reply(
        val code: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private val handlers = mutableMapOf<String, (Recorded) -> Reply>()
    val requests = mutableListOf<Recorded>()

    var offline = false

    fun on(
        hostAndPath: String,
        reply: (Recorded) -> Reply,
    ) {
        handlers[hostAndPath] = reply
    }

    fun json(
        hostAndPath: String,
        code: Int,
        body: String,
    ) = on(hostAndPath) { Reply(code, body.toByteArray()) }

    fun redirect(
        hostAndPath: String,
        location: String,
    ) = on(hostAndPath) { Reply(302, headers = mapOf("Location" to location)) }

    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(
                Interceptor { chain ->
                    if (offline) throw IOException("offline")
                    val recorded = record(chain.request())
                    requests.add(recorded)
                    val key = chain.request().url.host + chain.request().url.encodedPath
                    val reply = handlers[key]?.invoke(recorded) ?: Reply(404, """{"message":"Not Found"}""".toByteArray())
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(reply.code)
                        .message("fake")
                        .apply { reply.headers.forEach { (name, value) -> header(name, value) } }
                        .body(reply.body.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                },
            ).build()

    private fun record(request: Request) =
        Recorded(
            url = request.url.toString(),
            headers = request.headers.associate { (name, value) -> name.lowercase() to value },
        )

    companion object {
        const val REPO = "remcovolmer/Hirameki"
        const val LATEST = "api.github.com/repos/$REPO/releases/latest"

        fun downloadUrl(
            tag: String,
            name: String,
        ) = "https://github.com/$REPO/releases/download/$tag/$name"

        fun asset(
            version: String,
            suffix: String,
            size: Long = 1000,
            digest: String? = null,
        ): String {
            val name = "Brainiac-$version-$suffix.apk"
            val digestJson = digest?.let { ""","digest":"$it"""" }.orEmpty()
            return """{"name":"$name","size":$size,"state":"uploaded",""" +
                """"browser_download_url":"${downloadUrl("brainiac-v$version", name)}"$digestJson}"""
        }

        fun release(
            version: String,
            assets: List<String> = listOf(asset(version, "universal"), asset(version, "arm64-v8a"), asset(version, "x86_64")),
            draft: Boolean = false,
            prerelease: Boolean = false,
            body: String = "## What's new\n\n- **Faster** sync\n- See [the docs](https://example.test)",
            tag: String = "brainiac-v$version",
        ): String =
            """
            {"tag_name":"$tag","name":"Brainiac $version","draft":$draft,"prerelease":$prerelease,
             "body":${kotlinx.serialization.json.JsonPrimitive(body)},"html_url":"https://github.com/$REPO/releases/tag/$tag",
             "assets":[${assets.joinToString(",")}],"author":{"login":"someone"}}
            """.trimIndent()
    }
}
