// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.test.assertNull

/** [verifyApk]; reading the identity from an apk is PackageManager's job and is seen on a device. */
class ApkVerificationTest {
    private val installed = ApkIdentity("nl.remcovolmer.brainiac", 400_002_001, setOf("aa"))

    private fun candidate(
        packageName: String = installed.packageName,
        versionCode: Long = 400_003_000,
        signers: Set<String> = installed.signers,
    ) = ApkIdentity(packageName, versionCode, signers)

    @Test
    fun `a newer apk of the same app, kind and key passes`() {
        assertNull(verifyApk(candidate(), installed))
    }

    @Test
    fun `an unreadable apk is refused`() {
        assertEquals(UpdateFailure.NotAnApk, verifyApk(null, installed))
    }

    @Test
    fun `another package is refused`() {
        assertEquals(UpdateFailure.WrongPackage, verifyApk(candidate(packageName = "com.ichi2.anki"), installed))
    }

    @Test
    fun `the same or a lower versionCode is refused`() {
        assertEquals(UpdateFailure.NotNewer, verifyApk(candidate(versionCode = 400_002_001), installed))
        assertEquals(UpdateFailure.NotNewer, verifyApk(candidate(versionCode = 400_002_000), installed))
    }

    @Test
    fun `another kind of apk is refused, even with a higher versionCode`() {
        // universal 0.3.0 for an installed x86_64 0.2.1
        assertEquals(UpdateFailure.WrongVariant, verifyApk(candidate(versionCode = 3_000), installed))
        // arm64 for x86_64
        assertEquals(UpdateFailure.WrongVariant, verifyApk(candidate(versionCode = 300_003_000), installed))
    }

    @Test
    fun `another or a missing certificate is refused`() {
        assertEquals(UpdateFailure.WrongSignature, verifyApk(candidate(signers = setOf("bb")), installed))
        assertEquals(UpdateFailure.WrongSignature, verifyApk(candidate(signers = emptySet()), installed))
        assertEquals(UpdateFailure.WrongSignature, verifyApk(candidate(signers = setOf("aa", "bb")), installed))
    }
}
