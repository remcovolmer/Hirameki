// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** What decides whether an apk may replace the installed app. [signers] are sha-256 hex of the certificates. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val signers: Set<String>,
)

/** Reads the identity of the installed app and of a downloaded apk; behind an interface for tests. */
interface ApkInspector {
    suspend fun installed(): ApkIdentity

    /** Null when [file] is not an apk Android can read. */
    suspend fun archive(file: File): ApkIdentity?
}

/**
 * Null when [candidate] is a valid update of [installed]: the same package, the same kind of apk
 * (universal or per-ABI, see [ApkVariant.fromVersionCode]), a higher versionCode and the same
 * signing certificates. Otherwise the reason it is not.
 */
fun verifyApk(
    candidate: ApkIdentity?,
    installed: ApkIdentity,
): UpdateFailure? =
    when {
        candidate == null -> UpdateFailure.NotAnApk
        candidate.packageName != installed.packageName -> UpdateFailure.WrongPackage
        candidate.versionCode / ApkVariant.ABI_VERSION_CODE_OFFSET != installed.versionCode / ApkVariant.ABI_VERSION_CODE_OFFSET ->
            UpdateFailure.WrongVariant
        candidate.versionCode <= installed.versionCode -> UpdateFailure.NotNewer
        candidate.signers.isEmpty() || candidate.signers != installed.signers -> UpdateFailure.WrongSignature
        else -> null
    }

class PackageManagerApkInspector(
    context: Context,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : ApkInspector {
    private val context = context.applicationContext

    override suspend fun installed(): ApkIdentity =
        withContext(dispatcher) {
            installedPackageInfo(context, PackageManager.GET_SIGNING_CERTIFICATES).identity()
        }

    override suspend fun archive(file: File): ApkIdentity? =
        withContext(dispatcher) {
            val pm = context.packageManager
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(file.path, flags)
                }
            info?.identity()
        }

    private fun PackageInfo.identity(): ApkIdentity {
        val signers = signingInfo?.apkContentsSigners.orEmpty()
        return ApkIdentity(
            packageName = packageName,
            versionCode = longVersionCode,
            signers = signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet(),
        )
    }
}

/** The installed app's own PackageInfo; the versionCode in it is the per-ABI one, unlike BuildConfig's. */
internal fun installedPackageInfo(
    context: Context,
    flags: Int = 0,
): PackageInfo {
    val pm = context.packageManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(context.packageName, flags)
    }
}
