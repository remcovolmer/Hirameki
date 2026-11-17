// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/** REST API of the Brainiac service, as used by the Discover screen. */
interface DiscoverApi {
    /** `GET /api/v1/lessons` with the default filter `open`. */
    suspend fun openLessons(): List<LessonSummary>

    suspend fun lesson(id: Long): Lesson

    suspend fun markRead(lessonId: Long): Lesson

    suspend fun decide(
        proposalId: Long,
        request: DecisionRequest,
    ): Proposal

    suspend fun sources(): List<Source>

    suspend fun addSource(request: NewSourceRequest): Source

    suspend fun process(sourceId: Long): ProcessResponse
}

/** Where the service lives and how to authenticate. */
data class DiscoverConfig(
    val baseUrl: HttpUrl,
    val token: String,
) {
    override fun toString() = "DiscoverConfig(baseUrl=$baseUrl, token=***)"

    companion object {
        /**
         * Parses user input. Returns null when the URL is not a valid http(s) URL or the token is
         * blank. A URL without scheme is read as `http://`, matching the private host names the
         * service runs on.
         */
        fun parse(
            serverUrl: String,
            token: String,
        ): DiscoverConfig? {
            val trimmedUrl = serverUrl.trim()
            val trimmedToken = token.trim()
            if (trimmedUrl.isEmpty() || trimmedToken.isEmpty()) return null
            val withScheme = if ("://" in trimmedUrl) trimmedUrl else "http://$trimmedUrl"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            return DiscoverConfig(url, trimmedToken)
        }
    }
}

/** Every failure of a [DiscoverApi] call. [detail] is the service's `detail` message, if any. */
sealed class DiscoverApiException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    open val detail: String? get() = null

    /** The server could not be reached (offline, DNS, timeout). */
    class Network(
        cause: IOException,
    ) : DiscoverApiException("network error: ${cause.message}", cause)

    /** 401: token missing or wrong. */
    class Unauthorized : DiscoverApiException("unauthorized")

    /** 404 */
    class NotFound(
        override val detail: String?,
    ) : DiscoverApiException("not found: $detail")

    /** 409: e.g. a decision on a proposal that is no longer `nieuw`, or a double tap on "process". */
    class Conflict(
        override val detail: String?,
    ) : DiscoverApiException("conflict: $detail")

    /** 422: the service rejected the request body. */
    class InvalidRequest(
        override val detail: String?,
    ) : DiscoverApiException("invalid request: $detail")

    /** 503: e.g. the routine trigger is not configured on the server. */
    class ServiceUnavailable(
        override val detail: String?,
    ) : DiscoverApiException("service unavailable: $detail")

    /** Any other non-2xx status, including 502 from the routine trigger. */
    class Http(
        val code: Int,
        override val detail: String?,
    ) : DiscoverApiException("HTTP $code: $detail")

    /** The response body was not the JSON the contract describes. */
    class InvalidResponse(
        cause: Throwable,
    ) : DiscoverApiException("invalid response: ${cause.message}", cause)
}

/** [DiscoverApi] over OkHttp. */
class OkHttpDiscoverApi(
    private val config: DiscoverConfig,
    private val client: OkHttpClient = defaultClient,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : DiscoverApi {
    override suspend fun openLessons(): List<LessonSummary> = get<LessonsResponse>("lessons").lessons

    override suspend fun lesson(id: Long): Lesson = get("lessons/$id")

    override suspend fun markRead(lessonId: Long): Lesson = post("lessons/$lessonId/read", body = null)

    override suspend fun decide(
        proposalId: Long,
        request: DecisionRequest,
    ): Proposal = post("proposals/$proposalId/decision", jsonBody(request))

    override suspend fun sources(): List<Source> = get<SourcesResponse>("sources").sources

    override suspend fun addSource(request: NewSourceRequest): Source = post("sources", jsonBody(request))

    override suspend fun process(sourceId: Long): ProcessResponse = post("sources/$sourceId/process", body = null)

    private suspend inline fun <reified T> get(path: String): T = execute(request(path).get().build(), serializer())

    private suspend inline fun <reified T> post(
        path: String,
        body: RequestBody?,
    ): T = execute(request(path).post(body ?: EMPTY_BODY).build(), serializer())

    private inline fun <reified T> jsonBody(value: T): RequestBody = discoverJson.encodeToString(serializer(), value).toRequestBody(JSON)

    private fun request(path: String): Request.Builder {
        val url =
            config.baseUrl
                .newBuilder()
                .addPathSegments("api/v1/$path")
                .build()
        return Request
            .Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.token}")
            .header("Accept", "application/json")
    }

    private suspend fun <T> execute(
        request: Request,
        deserializer: KSerializer<T>,
    ): T =
        withContext(dispatcher) {
            Timber.d("Discover: %s %s", request.method, request.url.encodedPath)
            val (code, body) =
                try {
                    client.newCall(request).execute().use { response -> response.code to response.body.string() }
                } catch (e: IOException) {
                    Timber.w(e, "Discover: %s %s failed", request.method, request.url.encodedPath)
                    throw DiscoverApiException.Network(e)
                }
            if (code !in 200..299) throw errorFor(code, body)
            try {
                discoverJson.decodeFromString(deserializer, body)
            } catch (e: SerializationException) {
                throw DiscoverApiException.InvalidResponse(e)
            } catch (e: IllegalArgumentException) {
                throw DiscoverApiException.InvalidResponse(e)
            }
        }

    private fun errorFor(
        code: Int,
        body: String,
    ): DiscoverApiException {
        val detail =
            try {
                discoverJson.decodeFromString(ErrorResponse.serializer(), body).detail
            } catch (e: SerializationException) {
                Timber.d(e, "Discover: error body is not JSON")
                null
            } catch (e: IllegalArgumentException) {
                Timber.d(e, "Discover: error body is not JSON")
                null
            }
        return when (code) {
            401 -> DiscoverApiException.Unauthorized()
            404 -> DiscoverApiException.NotFound(detail)
            409 -> DiscoverApiException.Conflict(detail)
            422 -> DiscoverApiException.InvalidRequest(detail)
            503 -> DiscoverApiException.ServiceUnavailable(detail)
            else -> DiscoverApiException.Http(code, detail)
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)

        /** One client for the whole app, so connections are pooled across screens. */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
