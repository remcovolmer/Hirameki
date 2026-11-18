// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import com.ichi2.anki.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertNull

class UpdateVersionTest {
    @Test
    fun `versions compare numerically per part`() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.10") > v("0.2.9"))
        assertEquals(0, v("0.2.1").compareTo(v("0.2.1")))
        assertEquals("0.10.0", v("0.10.0").toString())
    }

    @Test
    fun `release tags follow brainiac-v and plain semver`() {
        assertEquals(UpdateVersion(0, 3, 0), UpdateVersion.fromTag("brainiac-v0.3.0"))
        assertEquals(UpdateVersion(12, 0, 7), UpdateVersion.fromTag("brainiac-v12.0.7"))
    }

    @Test
    fun `tags outside the schema are not versions`() {
        listOf(
            "",
            "v0.3.0",
            "0.3.0",
            "brainiac-0.3.0",
            "brainiac-v0.3",
            "brainiac-v0.3.0.1",
            "brainiac-v0.3.0-rc1",
            "brainiac-v0.3.0+build5",
            "brainiac-v01.3.0",
            "brainiac-v0.3.x",
            "brainiac-v 0.3.0",
            "brainiac-v0.3.0 ",
            "brainiac-v1234567890.0.0",
            "hirameki-v0.3.0",
        ).forEach { assertNull(UpdateVersion.fromTag(it), it) }
    }

    @Test
    fun `the installed versionName may carry a build suffix`() {
        assertEquals(UpdateVersion(0, 2, 1), UpdateVersion.fromVersionName("0.2.1"))
        assertEquals(UpdateVersion(0, 2, 1), UpdateVersion.fromVersionName("0.2.1-debug"))
        assertNull(UpdateVersion.fromVersionName(null))
        assertNull(UpdateVersion.fromVersionName("1.1.10alpha"))
    }

    @Test
    fun `the variant follows from the versionCode`() {
        val codes = ApkVariant.parseAbiCodes(BuildConfig.BRAINIAC_ABI_VERSION_CODES)
        // the same map as AnkiDroid/build.gradle.kts gives the release outputs
        assertEquals(mapOf(1 to "armeabi-v7a", 2 to "x86", 3 to "arm64-v8a", 4 to "x86_64"), codes)

        assertEquals(ApkVariant.UNIVERSAL, ApkVariant.fromVersionCode(2_001, codes))
        assertEquals(ApkVariant("arm64-v8a"), ApkVariant.fromVersionCode(300_002_001, codes))
        assertEquals(ApkVariant("x86_64"), ApkVariant.fromVersionCode(400_002_001, codes))
        assertNull(ApkVariant.fromVersionCode(900_002_001, codes))
    }

    @Test
    fun `asset names match the release workflow`() {
        assertEquals("Brainiac-0.3.0-universal.apk", apkAssetName(v("0.3.0"), ApkVariant.UNIVERSAL))
        assertEquals("Brainiac-0.3.0-x86_64.apk", apkAssetName(v("0.3.0"), ApkVariant("x86_64")))
    }

    @Test
    fun `malformed abi specs are skipped`() {
        assertEquals(mapOf(1 to "a"), ApkVariant.parseAbiCodes("a:1,b,c:x,:3,"))
    }

    @Test
    fun `release notes become short plain text`() {
        val notes =
            plainReleaseNotes(
                "## What's new\r\n\r\n- **Faster** sync\n* See [the docs](https://example.test) <br>\n\n\n\n`code` ![img](x.png)",
            )
        assertEquals("What's new\n\n• Faster sync\n• See the docs\n\ncode img", notes)

        assertEquals("", plainReleaseNotes(null))
        val long = plainReleaseNotes("x".repeat(1000), maxChars = 10)
        assertEquals("xxxxxxxxxx…", long)
    }

    private fun v(text: String) = UpdateVersion.parse(text)!!
}
