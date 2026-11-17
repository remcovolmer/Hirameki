// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** The fields of a GitHub release the updater reads. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    /** `sha256:<hex>`; GitHub computes it for assets uploaded since mid 2025. */
    val digest: String? = null,
    /** `uploaded` once the upload has finished. */
    val state: String? = null,
)

interface ReleaseSource {
    /**
     * The latest published release, or null when the repository has none yet (404).
     *
     * @throws UpdateException
     */
    suspend fun latestRelease(): GitHubRelease?
}

/** `GET /repos/<owner>/<repo>/releases/latest` of the public API, without a token. */
class GitHubReleaseSource(
    private val repo: String,
    private val client: OkHttpClient = UpdateHttp.client,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : ReleaseSource {
    init {
        require(REPO.matches(repo)) { "not an owner/repo: $repo" }
    }

    override suspend fun latestRelease(): GitHubRelease? =
        withContext(dispatcher) {
            val request =
                Request
                    .Builder()
                    .url("https://api.github.com/repos/$repo/releases/latest".toHttpUrl())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
            UpdateHttp.execute(client, request) { response ->
                when (response.code) {
                    200 -> parse(response.body.string())
                    404 -> null
                    403, 429 -> throw UpdateException(UpdateFailure.RateLimited)
                    else -> throw UpdateException(UpdateFailure.Http(response.code))
                }
            }
        }

    private fun parse(body: String): GitHubRelease =
        try {
            updateJson.decodeFromString(GitHubRelease.serializer(), body)
        } catch (e: SerializationException) {
            throw UpdateException(UpdateFailure.InvalidResponse, e)
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateFailure.InvalidResponse, e)
        }

    companion object {
        private val REPO = Regex("""[A-Za-z0-9-]+/[A-Za-z0-9._-]+""")
    }
}

/** The outcome of comparing the latest release with the installed app. */
sealed interface ReleaseCheck {
    data class Available(
        val update: AvailableUpdate,
    ) : ReleaseCheck

    /** Nothing newer; [latest] is the newest release that counts, if there is one. */
    data class UpToDate(
        val latest: UpdateVersion?,
    ) : ReleaseCheck

    /** A newer release exists, but without an apk for the installed variant. */
    data class NoMatchingAsset(
        val version: UpdateVersion,
    ) : ReleaseCheck
}

/**
 * Decides whether [release] is an update for the installed app. Drafts, pre-releases and tags
 * outside `brainiac-v<semver>` do not count. The apk must be the one of the installed [variant]
 * (null: unknown, so nothing fits) and must be served from this repository's release downloads.
 */
fun evaluateRelease(
    release: GitHubRelease?,
    repo: String,
    installed: UpdateVersion,
    variant: ApkVariant?,
): ReleaseCheck {
    if (release == null || release.draft || release.prerelease) return ReleaseCheck.UpToDate(null)
    val version = UpdateVersion.fromTag(release.tagName) ?: return ReleaseCheck.UpToDate(null)
    if (version <= installed) return ReleaseCheck.UpToDate(version)
    if (variant == null) return ReleaseCheck.NoMatchingAsset(version)
    val name = apkAssetName(version, variant)
    val downloadPrefix = "https://github.com/$repo/releases/download/"
    val asset =
        release.assets.firstOrNull {
            it.name == name &&
                (it.state == null || it.state == "uploaded") &&
                it.size > 0 &&
                it.browserDownloadUrl.startsWith(downloadPrefix)
        } ?: return ReleaseCheck.NoMatchingAsset(version)
    return ReleaseCheck.Available(
        AvailableUpdate(
            version = version,
            notes = plainReleaseNotes(release.body),
            asset = ReleaseAsset(asset.name, asset.size, asset.browserDownloadUrl, sha256Of(asset.digest)),
        ),
    )
}

private val SHA256_DIGEST = Regex("""sha256:([0-9a-fA-F]{64})""")

/** The hex of a `sha256:<hex>` digest, lowercase; null for no digest or another algorithm. */
fun sha256Of(digest: String?): String? =
    digest
        ?.trim()
        ?.let { SHA256_DIGEST.matchEntire(it) }
        ?.groupValues
        ?.get(1)
        ?.lowercase()
