// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.LATEST
import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.REPO
import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.asset
import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.release
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubReleasesTest {
    private val github = FakeGitHub()

    private fun TestScope.source() = GitHubReleaseSource(REPO, github.client, StandardTestDispatcher(testScheduler))

    private suspend fun TestScope.failure(): UpdateFailure = assertFailsWith<UpdateException> { source().latestRelease() }.failure

    @Test
    fun `the latest release is read without anything about the user`() =
        runTest {
            github.json(LATEST, 200, release("0.3.0"))

            val release = assertNotNull(source().latestRelease())

            assertEquals("brainiac-v0.3.0", release.tagName)
            assertEquals(3, release.assets.size)
            val request = github.requests.single()
            assertEquals("https://api.github.com/repos/remcovolmer/Hirameki/releases/latest", request.url)
            assertEquals("Brainiac-Updater", request.headers["user-agent"])
            assertEquals("application/vnd.github+json", request.headers["accept"])
            assertFalse("authorization" in request.headers)
            assertFalse("cookie" in request.headers)
        }

    @Test
    fun `a repository without releases has no latest release`() =
        runTest {
            github.json(LATEST, 404, """{"message":"Not Found"}""")

            assertNull(source().latestRelease())
        }

    @Test
    fun `403 and 429 are the rate limit`() =
        runTest {
            github.json(LATEST, 403, """{"message":"API rate limit exceeded"}""")
            assertEquals(UpdateFailure.RateLimited, failure())

            github.json(LATEST, 429, "")
            assertEquals(UpdateFailure.RateLimited, failure())
        }

    @Test
    fun `other statuses keep their code`() =
        runTest {
            github.json(LATEST, 502, "Bad gateway")

            assertEquals(UpdateFailure.Http(502), failure())
        }

    @Test
    fun `broken json is an invalid response`() =
        runTest {
            github.json(LATEST, 200, """{"tag_name": "brainiac-v0.3.0", "assets": [""")
            assertEquals(UpdateFailure.InvalidResponse, failure())

            github.json(LATEST, 200, """{"name": "no tag"}""")
            assertEquals(UpdateFailure.InvalidResponse, failure())
        }

    @Test
    fun `no connection is a network failure`() =
        runTest {
            github.offline = true

            assertEquals(UpdateFailure.Network, failure())
        }

    @Test
    fun `a redirect to another GitHub host is followed, one elsewhere refused`() =
        runTest {
            github.redirect(LATEST, "https://api.github.com/repositories/1/releases/latest")
            github.json("api.github.com/repositories/1/releases/latest", 200, release("0.3.0"))
            assertEquals("brainiac-v0.3.0", source().latestRelease()?.tagName)

            github.redirect(LATEST, "https://evil.example/releases/latest")
            assertEquals(UpdateFailure.HostNotAllowed, failure())

            github.redirect(LATEST, "http://api.github.com/repos/$REPO/releases/latest")
            assertEquals(UpdateFailure.HostNotAllowed, failure())
        }

    // ---- evaluateRelease ----

    private fun parsed(json: String) = updateJson.decodeFromString(GitHubRelease.serializer(), json)

    private val installed = UpdateVersion(0, 2, 1)

    @Test
    fun `a newer release offers the apk of the installed abi`() {
        val check = evaluateRelease(parsed(release("0.3.0")), REPO, installed, ApkVariant("x86_64"))

        val update = assertIs<ReleaseCheck.Available>(check).update
        assertEquals(UpdateVersion(0, 3, 0), update.version)
        assertEquals("Brainiac-0.3.0-x86_64.apk", update.asset.name)
        assertEquals("https://github.com/$REPO/releases/download/brainiac-v0.3.0/Brainiac-0.3.0-x86_64.apk", update.asset.url)
        assertEquals("What's new\n\n• Faster sync\n• See the docs", update.notes)
    }

    @Test
    fun `the universal build gets the universal apk, never a per-abi one`() {
        val universal = evaluateRelease(parsed(release("0.3.0")), REPO, installed, ApkVariant.UNIVERSAL)
        assertEquals("Brainiac-0.3.0-universal.apk", assertIs<ReleaseCheck.Available>(universal).update.asset.name)

        val onlyAbi = release("0.3.0", assets = listOf(asset("0.3.0", "arm64-v8a")))
        assertEquals(
            ReleaseCheck.NoMatchingAsset(UpdateVersion(0, 3, 0)),
            evaluateRelease(parsed(onlyAbi), REPO, installed, ApkVariant.UNIVERSAL),
        )
    }

    @Test
    fun `a per-abi build never gets the universal apk`() {
        val onlyUniversal = release("0.3.0", assets = listOf(asset("0.3.0", "universal")))

        val check = evaluateRelease(parsed(onlyUniversal), REPO, installed, ApkVariant("arm64-v8a"))

        assertEquals(ReleaseCheck.NoMatchingAsset(UpdateVersion(0, 3, 0)), check)
    }

    @Test
    fun `an unknown variant gets nothing`() {
        assertIs<ReleaseCheck.NoMatchingAsset>(evaluateRelease(parsed(release("0.3.0")), REPO, installed, null))
    }

    @Test
    fun `drafts, pre-releases and foreign tags do not count`() {
        val up = ReleaseCheck.UpToDate(null)
        assertEquals(up, evaluateRelease(parsed(release("0.3.0", draft = true)), REPO, installed, ApkVariant.UNIVERSAL))
        assertEquals(up, evaluateRelease(parsed(release("0.3.0", prerelease = true)), REPO, installed, ApkVariant.UNIVERSAL))
        assertEquals(up, evaluateRelease(parsed(release("0.3.0", tag = "v0.3.0")), REPO, installed, ApkVariant.UNIVERSAL))
        assertEquals(up, evaluateRelease(parsed(release("0.3.0", tag = "brainiac-v0.3.0-rc1")), REPO, installed, ApkVariant.UNIVERSAL))
        assertEquals(up, evaluateRelease(null, REPO, installed, ApkVariant.UNIVERSAL))
    }

    @Test
    fun `the same or an older version is up to date`() {
        assertEquals(
            ReleaseCheck.UpToDate(UpdateVersion(0, 2, 1)),
            evaluateRelease(parsed(release("0.2.1")), REPO, installed, ApkVariant.UNIVERSAL),
        )
        assertEquals(
            ReleaseCheck.UpToDate(UpdateVersion(0, 1, 9)),
            evaluateRelease(parsed(release("0.1.9")), REPO, installed, ApkVariant.UNIVERSAL),
        )
        // numeric, not textual: 0.10.0 is newer than 0.9.0
        assertIs<ReleaseCheck.Available>(
            evaluateRelease(parsed(release("0.10.0")), REPO, UpdateVersion(0, 9, 0), ApkVariant.UNIVERSAL),
        )
    }

    @Test
    fun `an asset outside this repository's downloads, unfinished or empty is not offered`() {
        val foreign =
            """{"name":"Brainiac-0.3.0-universal.apk","size":1000,
               "browser_download_url":"https://github.com/someone/else/releases/download/brainiac-v0.3.0/Brainiac-0.3.0-universal.apk"}"""
        val unfinished = asset("0.3.0", "universal").replace("\"uploaded\"", "\"starter\"")
        val empty = asset("0.3.0", "universal", size = 0)
        listOf(foreign, unfinished, empty).forEach { candidate ->
            val check = evaluateRelease(parsed(release("0.3.0", assets = listOf(candidate))), REPO, installed, ApkVariant.UNIVERSAL)
            assertIs<ReleaseCheck.NoMatchingAsset>(check, candidate)
        }
    }

    @Test
    fun `a sha256 digest is passed on, other digests are not`() {
        val hex = "AB".repeat(32)
        val withDigest = release("0.3.0", assets = listOf(asset("0.3.0", "universal", digest = "sha256:$hex")))
        val check = evaluateRelease(parsed(withDigest), REPO, installed, ApkVariant.UNIVERSAL)
        assertEquals(hex.lowercase(), assertIs<ReleaseCheck.Available>(check).update.asset.sha256)

        assertNull(sha256Of("sha512:$hex"))
        assertNull(sha256Of("sha256:abc"))
        assertNull(sha256Of(null))
    }
}
