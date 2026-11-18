// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

interface ApkDownloader {
    /**
     * Downloads [asset] into the app's cache and returns the file once its size (and sha256, when
     * the release gives one) match. A partial download from an earlier attempt is resumed; every
     * other file in the download folder is removed.
     *
     * @throws UpdateException
     */
    suspend fun download(
        asset: ReleaseAsset,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File

    /** Deletes a download that failed the checks after [download]. */
    suspend fun discard(file: File)

    /** Deletes every download, e.g. once the update is installed. */
    suspend fun clear()
}

/** [ApkDownloader] over OkHttp, into [dir] (a folder in the app's cache). */
class OkHttpApkDownloader(
    private val dir: File,
    private val client: OkHttpClient = UpdateHttp.client,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : ApkDownloader {
    override suspend fun download(
        asset: ReleaseAsset,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File =
        withContext(dispatcher) {
            // the name comes from the API and becomes a file name
            if (!SAFE_NAME.matches(asset.name)) throw UpdateException(UpdateFailure.InvalidResponse)
            if (!dir.isDirectory && !dir.mkdirs()) throw UpdateException(UpdateFailure.Storage)
            val target = File(dir, asset.name)
            val part = File(dir, asset.name + PART_SUFFIX)
            dir.listFiles()?.filter { it != target && it != part }?.forEach { it.deleteRecursively() }

            if (target.isFile) {
                if (target.length() == asset.size && digestMatches(target, asset)) {
                    onProgress(asset.size, asset.size)
                    return@withContext target
                }
                target.delete()
            }
            if (part.length() > asset.size) part.delete()
            if (part.length() < asset.size) fetch(asset, part, onProgress)

            if (part.length() != asset.size) {
                part.delete()
                throw UpdateException(UpdateFailure.SizeMismatch)
            }
            if (!digestMatches(part, asset)) {
                part.delete()
                throw UpdateException(UpdateFailure.DigestMismatch)
            }
            if (!part.renameTo(target)) throw UpdateException(UpdateFailure.Storage)
            target
        }

    override suspend fun discard(file: File) {
        withContext(dispatcher) { file.delete() }
    }

    override suspend fun clear() {
        withContext(dispatcher) { dir.deleteRecursively() }
    }

    private suspend fun fetch(
        asset: ReleaseAsset,
        part: File,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ) {
        val job = currentCoroutineContext()[Job]
        val offset = part.length()
        val url = asset.url.toHttpUrlOrNull() ?: throw UpdateException(UpdateFailure.HostNotAllowed)
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }
                .build()
        UpdateHttp.execute(client, request) { response ->
            val append =
                when (response.code) {
                    // the server resumed where the partial file ends
                    206 -> offset > 0 && contentRangeStart(response) == offset
                    // the server ignored the range: start over
                    200 -> false
                    else -> {
                        // a 416 means the partial file no longer fits the asset
                        if (response.code == 416) part.delete()
                        throw UpdateException(UpdateFailure.Http(response.code))
                    }
                }
            if (response.code == 206 && !append) {
                part.delete()
                throw UpdateException(UpdateFailure.InvalidResponse)
            }
            if (offset > 0 && !append) Timber.i("Update: server ignored the range, downloading again")
            var written = if (append) offset else 0L
            onProgress(written, asset.size)
            val source = response.body.byteStream()
            val tooLarge =
                FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        job?.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (written + read > asset.size) return@use true
                        out.write(buffer, 0, read)
                        written += read
                        onProgress(written, asset.size)
                    }
                    false
                }
            if (tooLarge) {
                part.delete()
                throw UpdateException(UpdateFailure.SizeMismatch)
            }
        }
    }

    private fun contentRangeStart(response: Response): Long? =
        response
            .header("Content-Range")
            ?.let { CONTENT_RANGE.matchEntire(it.trim()) }
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private fun digestMatches(
        file: File,
        asset: ReleaseAsset,
    ): Boolean = asset.sha256 == null || sha256Hex(file) == asset.sha256

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
        private val SAFE_NAME = Regex("""[A-Za-z0-9][A-Za-z0-9._-]*\.apk""")
        private val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+|\*)""")
    }
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String {
    val hex = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xff
        hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0f])
    }
    return hex.toString()
}
