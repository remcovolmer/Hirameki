// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import java.io.File

class MemoryUpdateStore : UpdateStore {
    override var autoCheckEnabled = true
    override var lastCheckMillis = 0L
    override var snoozedVersion: UpdateVersion? = null
    override var snoozedAtMillis = 0L
    override var knownUpdate: AvailableUpdate? = null
}

/** Answers with [release], or throws [failure]; counts the requests. */
class ScriptedReleaseSource : ReleaseSource {
    var release: GitHubRelease? = null
    var failure: UpdateFailure? = null
    var calls = 0

    override suspend fun latestRelease(): GitHubRelease? {
        calls++
        failure?.let { throw UpdateException(it) }
        return release
    }
}

class FakeDownloader(
    private val dir: File,
) : ApkDownloader {
    var failure: UpdateFailure? = null
    val discarded = mutableListOf<File>()
    var cleared = 0

    override suspend fun download(
        asset: ReleaseAsset,
        onProgress: (bytes: Long, total: Long) -> Unit,
    ): File {
        onProgress(asset.size / 2, asset.size)
        failure?.let { throw UpdateException(it) }
        onProgress(asset.size, asset.size)
        return File(dir, asset.name).apply { writeText("apk") }
    }

    override suspend fun discard(file: File) {
        discarded += file
        file.delete()
    }

    override suspend fun clear() {
        cleared++
    }
}

class FakeInspector(
    var installed: ApkIdentity,
    var archive: ApkIdentity?,
) : ApkInspector {
    override suspend fun installed() = installed

    override suspend fun archive(file: File) = archive
}

class FakeInstaller : ApkInstaller {
    var allowed = true
    var failure: UpdateFailure? = null
    val installed = mutableListOf<File>()

    override fun canInstallPackages() = allowed

    override suspend fun install(file: File) {
        failure?.let { throw UpdateException(it) }
        installed += file
    }
}
