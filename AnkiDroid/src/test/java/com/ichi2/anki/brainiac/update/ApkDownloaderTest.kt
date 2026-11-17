// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.downloadUrl
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ApkDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val github = FakeGitHub()
    private val apk = Random(42).nextBytes(10_000)
    private val name = "Brainiac-0.3.0-x86_64.apk"
    private val githubPath = "github.com/remcovolmer/Hirameki/releases/download/brainiac-v0.3.0/$name"
    private val storagePath = "release-assets.githubusercontent.com/github-production-release-asset/1/abc"
    private val dir by lazy { File(tmp.root, "brainiac-update") }

    private fun asset(
        size: Long = apk.size.toLong(),
        sha256: String? = sha256(apk),
        url: String = downloadUrl("brainiac-v0.3.0", name),
    ) = ReleaseAsset(name, size, url, sha256)

    private fun TestScope.downloader() = OkHttpApkDownloader(dir, github.client, StandardTestDispatcher(testScheduler))

    /** github.com redirects to the storage host, which honours Range like the real one. */
    private fun serve(
        bytes: ByteArray = apk,
        honourRange: Boolean = true,
    ) {
        github.redirect(githubPath, "https://$storagePath?sig=x")
        github.on(storagePath) { request ->
            val range = request.headers["range"]?.let { Regex("""bytes=(\d+)-""").matchEntire(it) }
            if (range != null && honourRange) {
                val start = range.groupValues[1].toInt()
                FakeGitHub.Reply(
                    206,
                    bytes.copyOfRange(start, bytes.size),
                    mapOf("Content-Range" to "bytes $start-${bytes.size - 1}/${bytes.size}"),
                )
            } else {
                FakeGitHub.Reply(200, bytes)
            }
        }
    }

    private suspend fun TestScope.failure(asset: ReleaseAsset = asset()): UpdateFailure =
        assertFailsWith<UpdateException> { downloader().download(asset) { _, _ -> } }.failure

    @Test
    fun `the apk is downloaded through the redirect and checked`() =
        runTest {
            serve()
            File(dir, "Brainiac-0.2.0-x86_64.apk").apply {
                parentFile!!.mkdirs()
                writeText("old")
            }
            val progress = mutableListOf<Long>()

            val file = downloader().download(asset()) { bytes, total -> progress += bytes.also { assertEquals(apk.size.toLong(), total) } }

            assertArrayEquals(apk, file.readBytes())
            assertEquals(name, file.name)
            assertEquals(listOf(file), dir.listFiles()!!.toList())
            assertEquals(apk.size.toLong(), progress.last())
            assertEquals(
                listOf(
                    "https://github.com/remcovolmer/Hirameki/releases/download/brainiac-v0.3.0/$name",
                ),
                github.requests.take(1).map {
                    it.url
                },
            )
            assertTrue(github.requests.all { it.headers["user-agent"] == "Brainiac-Updater" })
        }

    @Test
    fun `a redirect outside GitHub is refused`() =
        runTest {
            github.redirect(githubPath, "https://downloads.example/$name")

            assertEquals(UpdateFailure.HostNotAllowed, failure())
            assertEquals(1, github.requests.size)
        }

    @Test
    fun `plain http is refused before any request`() =
        runTest {
            serve()

            assertEquals(UpdateFailure.HostNotAllowed, failure(asset(url = "http://$githubPath")))
            assertTrue(github.requests.isEmpty())
        }

    @Test
    fun `a short download fails on its size and is removed`() =
        runTest {
            serve(apk.copyOf(9_000))

            assertEquals(UpdateFailure.SizeMismatch, failure())
            assertTrue(dir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `a download larger than announced fails on its size`() =
        runTest {
            serve(apk + ByteArray(10))

            assertEquals(UpdateFailure.SizeMismatch, failure())
            assertTrue(dir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `a download with another digest is removed`() =
        runTest {
            serve()

            assertEquals(UpdateFailure.DigestMismatch, failure(asset(sha256 = "0".repeat(64))))
            assertTrue(dir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `without a digest the size alone decides`() =
        runTest {
            serve()

            val file = downloader().download(asset(sha256 = null)) { _, _ -> }

            assertArrayEquals(apk, file.readBytes())
        }

    @Test
    fun `a partial download is resumed with a range request`() =
        runTest {
            serve()
            dir.mkdirs()
            File(dir, "$name.part").writeBytes(apk.copyOf(4_000))

            val file = downloader().download(asset()) { _, _ -> }

            assertArrayEquals(apk, file.readBytes())
            assertEquals("bytes=4000-", github.requests.last().headers["range"])
        }

    @Test
    fun `a server that ignores the range sends everything again`() =
        runTest {
            serve(honourRange = false)
            dir.mkdirs()
            File(dir, "$name.part").writeBytes(apk.copyOf(4_000))

            val file = downloader().download(asset()) { _, _ -> }

            assertArrayEquals(apk, file.readBytes())
        }

    @Test
    fun `a corrupt partial download is caught by the digest`() =
        runTest {
            serve()
            dir.mkdirs()
            File(dir, "$name.part").writeBytes(ByteArray(4_000))

            assertEquals(UpdateFailure.DigestMismatch, failure())
            assertTrue(dir.listFiles().isNullOrEmpty())
        }

    @Test
    fun `a finished download is reused without a request`() =
        runTest {
            dir.mkdirs()
            File(dir, name).writeBytes(apk)

            val file = downloader().download(asset()) { _, _ -> }

            assertArrayEquals(apk, file.readBytes())
            assertTrue(github.requests.isEmpty())
        }

    @Test
    fun `an error status is reported`() =
        runTest {
            github.on(githubPath) { FakeGitHub.Reply(500) }

            assertEquals(UpdateFailure.Http(500), failure())
        }

    @Test
    fun `an asset name with a path is refused`() =
        runTest {
            val bad = ReleaseAsset("../evil.apk", 10, downloadUrl("brainiac-v0.3.0", "evil.apk"))

            assertEquals(UpdateFailure.InvalidResponse, failure(bad))
            assertFalse(File(tmp.root, "evil.apk").exists())
        }

    @Test
    fun `clear removes every download`() =
        runTest {
            serve()
            val downloader = downloader()
            downloader.download(asset()) { _, _ -> }

            downloader.clear()

            assertFalse(dir.exists())
            assertNull(dir.listFiles())
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}
