// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP for the updater: https to GitHub only, redirects followed by hand so every hop is checked
 * against [ALLOWED_HOSTS], and nothing about the user in the request (a generic User-Agent, no
 * token, no identifiers).
 */
internal object UpdateHttp {
    const val USER_AGENT = "Brainiac-Updater"
    private const val MAX_REDIRECTS = 5

    /** The API, and the hosts a release download redirects to. */
    val ALLOWED_HOSTS =
        setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

    fun isAllowed(url: HttpUrl): Boolean = url.isHttps && url.host in ALLOWED_HOSTS

    /** Redirects are off: [execute] follows them itself, checking each hop. */
    val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Sends [request], follows redirects within the allowed hosts (keeping the headers, e.g.
     * `Range`), and hands the final response to [block]. An [IOException] on the way, also one
     * thrown by [block] while reading the body, becomes [UpdateFailure.Network].
     */
    fun <T> execute(
        client: OkHttpClient,
        request: Request,
        block: (Response) -> T,
    ): T {
        var current = request
        repeat(MAX_REDIRECTS + 1) {
            if (!isAllowed(current.url)) {
                Timber.w("Update: refused %s://%s", current.url.scheme, current.url.host)
                throw UpdateException(UpdateFailure.HostNotAllowed)
            }
            val outgoing = current.newBuilder().header("User-Agent", USER_AGENT).build()
            val response =
                try {
                    client.newCall(outgoing).execute()
                } catch (e: IOException) {
                    throw UpdateException(UpdateFailure.Network, e)
                }
            if (response.isRedirect) {
                val location = response.header("Location")
                response.close()
                val next = location?.let { current.url.resolve(it) } ?: throw UpdateException(UpdateFailure.InvalidResponse)
                current = current.newBuilder().url(next).build()
                return@repeat
            }
            return response.use {
                try {
                    block(it)
                } catch (e: IOException) {
                    throw UpdateException(UpdateFailure.Network, e)
                }
            }
        }
        throw UpdateException(UpdateFailure.InvalidResponse)
    }
}
