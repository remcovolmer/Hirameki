// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A Brainiac release version `major.minor.patch`, compared numerically (0.10.0 > 0.9.0). */
@Serializable
data class UpdateVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int =
        compareValuesBy(this, other, UpdateVersion::major, UpdateVersion::minor, UpdateVersion::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** Release tags are `brainiac-v<major.minor.patch>` (release_apk.yml). */
        const val TAG_PREFIX = "brainiac-v"

        // no leading zeros, at most 9 digits per part so every part fits an Int
        private val CORE = Regex("""(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""")

        fun parse(text: String): UpdateVersion? =
            CORE.matchEntire(text)?.let { m ->
                UpdateVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            }

        /**
         * The version of a release tag, or null for a tag outside the schema. A pre-release suffix
         * (`brainiac-v0.3.0-rc1`) is outside it as well: pre-releases are never offered.
         */
        fun fromTag(tag: String): UpdateVersion? = if (tag.startsWith(TAG_PREFIX)) parse(tag.removePrefix(TAG_PREFIX)) else null

        /** The installed versionName; a build suffix such as `-debug` is ignored. */
        fun fromVersionName(name: String?): UpdateVersion? = name?.let { parse(it.substringBefore('-')) }
    }
}

/** Which kind of apk is installed: one ABI, or the universal build ([abi] null). */
data class ApkVariant(
    val abi: String?,
) {
    /** The last part of the asset name, `Brainiac-<version>-<suffix>.apk`. */
    val assetSuffix: String get() = abi ?: "universal"

    companion object {
        val UNIVERSAL = ApkVariant(null)
        const val ABI_VERSION_CODE_OFFSET = 100_000_000L

        /** Parses `BuildConfig.BRAINIAC_ABI_VERSION_CODES`, `armeabi-v7a:1,x86:2,...`, into code to ABI. */
        fun parseAbiCodes(spec: String): Map<Int, String> =
            spec
                .split(',')
                .mapNotNull { entry ->
                    val parts = entry.split(':')
                    val code = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (parts.size != 2 || code == null || parts[0].isBlank()) null else code to parts[0].trim()
                }.toMap()

        // build.gradle.kts: per-abi apks get abi code * 10^8 + base, universal and debug apks the base, so code / 10^8 is the kind
        fun fromVersionCode(
            versionCode: Long,
            abiCodes: Map<Int, String>,
        ): ApkVariant? {
            val kind = versionCode / ABI_VERSION_CODE_OFFSET
            return if (kind == 0L) UNIVERSAL else abiCodes[kind.toInt()]?.let(::ApkVariant)
        }
    }
}

/** The name the release workflow gives the apk of [variant]. */
fun apkAssetName(
    version: UpdateVersion,
    variant: ApkVariant,
) = "Brainiac-$version-${variant.assetSuffix}.apk"

/** The apk of a release that fits the installed variant. [sha256] is lowercase hex, when GitHub gave a digest. */
@Serializable
data class ReleaseAsset(
    val name: String,
    val size: Long,
    val url: String,
    val sha256: String? = null,
)

/** A newer release with an apk for the installed variant. [notes] are plain text, already shortened. */
@Serializable
data class AvailableUpdate(
    val version: UpdateVersion,
    val notes: String,
    val asset: ReleaseAsset,
)

/** Why a check, download or installation did not work out; each has its own text in the UI. */
sealed interface UpdateFailure {
    /** GitHub could not be reached, or the connection broke off. */
    data object Network : UpdateFailure

    /** 403 or 429 from GitHub: the unauthenticated rate limit (60 requests an hour per IP). */
    data object RateLimited : UpdateFailure

    data class Http(
        val status: Int,
    ) : UpdateFailure

    /** The API answered with something that is not the release JSON. */
    data object InvalidResponse : UpdateFailure

    /** A request or redirect pointed outside https on GitHub's hosts. */
    data object HostNotAllowed : UpdateFailure

    data object SizeMismatch : UpdateFailure

    data object DigestMismatch : UpdateFailure

    data object NotAnApk : UpdateFailure

    data object WrongPackage : UpdateFailure

    data object NotNewer : UpdateFailure

    /** Universal versus per-ABI: Android would refuse it, the versionCodes are on different lines. */
    data object WrongVariant : UpdateFailure

    data object WrongSignature : UpdateFailure

    data object Storage : UpdateFailure

    /** The user did not allow Brainiac to install apps. */
    data object PermissionDenied : UpdateFailure

    /** PackageInstaller reported a failure; [message] is Android's reason, if any. */
    data class InstallFailed(
        val status: Int,
        val message: String?,
    ) : UpdateFailure
}

class UpdateException(
    val failure: UpdateFailure,
    cause: Throwable? = null,
) : Exception(failure.toString(), cause)

internal val updateJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val HTML_TAG = Regex("""<[^>\n]+>""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s*""")
private val BULLET = Regex("""^(\s*)[*+-]\s+""")
private val EMPHASIS = Regex("""\*\*|__|`""")
private val BLANK_LINES = Regex("""\n{3,}""")

/**
 * Release notes as plain text: the markdown of a GitHub release without headings, links, emphasis
 * and HTML, shortened to [maxChars] (with an ellipsis).
 */
fun plainReleaseNotes(
    markdown: String?,
    maxChars: Int = 600,
): String {
    if (markdown.isNullOrBlank()) return ""
    val text =
        markdown
            .replace("\r\n", "\n")
            .replace(IMAGE, "\$1")
            .replace(LINK, "\$1")
            .replace(HTML_TAG, "")
            .lines()
            .joinToString("\n") { line -> line.trimEnd().replace(HEADING, "").replace(BULLET, "\$1• ") }
            .replace(EMPHASIS, "")
            .replace(BLANK_LINES, "\n\n")
            .trim()
    return if (text.length <= maxChars) text else text.take(maxChars).trimEnd() + "…"
}
