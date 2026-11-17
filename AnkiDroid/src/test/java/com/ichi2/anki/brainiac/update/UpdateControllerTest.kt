// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.REPO
import com.ichi2.anki.brainiac.update.FakeGitHub.Companion.release
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The check, "Later", and the download-verify-install flow of [UpdateController], with fakes around it. */
@RunWith(AndroidJUnit4::class)
class UpdateControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = MemoryUpdateStore()
    private val releases = ScriptedReleaseSource().apply { release = parsed(release("0.3.0")) }
    private val downloader by lazy { FakeDownloader(tmp.root) }
    private val installedIdentity = ApkIdentity("nl.remcovolmer.brainiac", 400_002_001, setOf("key"))
    private val inspector = FakeInspector(installedIdentity, ApkIdentity("nl.remcovolmer.brainiac", 400_003_000, setOf("key")))
    private val installer = FakeInstaller()
    private val events = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 8)
    private var clock = 1_000_000_000_000L

    private fun TestScope.controller(
        installed: InstalledApp = InstalledApp("0.2.1", UpdateVersion(0, 2, 1), ApkVariant("x86_64")),
        supported: Boolean = true,
    ) = UpdateController(
        repo = REPO,
        installed = installed,
        supported = supported,
        store = store,
        releases = releases,
        downloader = downloader,
        inspector = inspector,
        installer = installer,
        installEvents = events,
        scope = backgroundScope,
        now = { clock },
    )

    private fun parsed(json: String) = updateJson.decodeFromString(GitHubRelease.serializer(), json)

    private val UpdateController.value get() = state.value

    // ---- checking ----

    @Test
    fun `opening the main screen checks and shows the banner`() =
        runTest {
            val c = controller()

            c.onShown()
            runCurrent()

            assertEquals(1, releases.calls)
            assertTrue(c.value.bannerVisible)
            assertEquals(UpdateVersion(0, 3, 0), c.value.available?.version)
            assertEquals(
                "Brainiac-0.3.0-x86_64.apk",
                c.value.available
                    ?.asset
                    ?.name,
            )
            // automatic checks leave the settings status alone
            assertEquals(CheckStatus.Idle, c.value.check)
            assertEquals(clock, store.lastCheckMillis)
        }

    @Test
    fun `the automatic check runs at most once per 24 hours`() =
        runTest {
            val c = controller()
            c.onShown()
            runCurrent()

            clock += UpdatePolicy.AUTO_CHECK_INTERVAL_MILLIS - 1
            c.onShown()
            runCurrent()
            assertEquals(1, releases.calls)

            clock += 1
            c.onShown()
            runCurrent()
            assertEquals(2, releases.calls)
        }

    @Test
    fun `the 24 hour limit holds across launches`() =
        runTest {
            controller().onShown()
            runCurrent()

            clock += 60_000
            val relaunched = controller()
            relaunched.onShown()
            runCurrent()

            assertEquals(1, releases.calls)
            // the update found before is still shown, from the store
            assertTrue(relaunched.value.bannerVisible)
        }

    @Test
    fun `with the switch off only the manual check asks GitHub`() =
        runTest {
            val c = controller()
            c.setAutoCheck(false)

            c.onShown()
            runCurrent()
            assertEquals(0, releases.calls)
            assertFalse(store.autoCheckEnabled)

            c.checkNow()
            runCurrent()
            assertEquals(1, releases.calls)
            assertEquals(CheckStatus.Available(UpdateVersion(0, 3, 0)), c.value.check)
        }

    @Test
    fun `the manual check ignores the 24 hour limit`() =
        runTest {
            val c = controller()
            c.onShown()
            runCurrent()

            c.checkNow()
            runCurrent()

            assertEquals(2, releases.calls)
        }

    @Test
    fun `failures of the automatic check are silent, of the manual one reported`() =
        runTest {
            releases.failure = UpdateFailure.RateLimited
            val c = controller()

            c.onShown()
            runCurrent()
            assertEquals(CheckStatus.Idle, c.value.check)
            assertFalse(c.value.bannerVisible)

            c.checkNow()
            runCurrent()
            assertEquals(CheckStatus.Failed(UpdateFailure.RateLimited), c.value.check)
        }

    @Test
    fun `a rate limit counts as a check, no connection does not`() =
        runTest {
            releases.failure = UpdateFailure.Network
            val c = controller()
            c.onShown()
            runCurrent()
            assertEquals(0L, store.lastCheckMillis)

            c.onShown()
            runCurrent()
            assertEquals(2, releases.calls)

            releases.failure = UpdateFailure.RateLimited
            c.onShown()
            runCurrent()
            assertEquals(clock, store.lastCheckMillis)
        }

    @Test
    fun `the manual check reports up to date and a missing apk`() =
        runTest {
            releases.release = parsed(release("0.2.1"))
            val c = controller()
            c.checkNow()
            runCurrent()
            assertEquals(CheckStatus.UpToDate("0.2.1"), c.value.check)

            releases.release = parsed(release("0.3.0", assets = listOf(FakeGitHub.asset("0.3.0", "universal"))))
            c.checkNow()
            runCurrent()
            assertEquals(CheckStatus.NoMatchingAsset(UpdateVersion(0, 3, 0), "x86_64"), c.value.check)
            assertFalse(c.value.bannerVisible)
            assertNull(store.knownUpdate)
        }

    @Test
    fun `a build without a release version is not updated`() =
        runTest {
            val c = controller(installed = InstalledApp("1.1.10alpha", null, ApkVariant.UNIVERSAL))

            c.checkNow()
            runCurrent()

            assertEquals(CheckStatus.UnknownInstall, c.value.check)
            assertEquals(0, releases.calls)
        }

    @Test
    fun `a build that does not update itself never asks GitHub`() =
        runTest {
            val c = controller(supported = false)

            c.onShown()
            c.checkNow()
            runCurrent()

            assertEquals(0, releases.calls)
            assertFalse(c.value.bannerVisible)
        }

    @Test
    fun `an update installed meanwhile is forgotten and its download removed`() =
        runTest {
            controller().onShown()
            runCurrent()
            assertNotNull(store.knownUpdate)

            val relaunched = controller(installed = InstalledApp("0.3.0", UpdateVersion(0, 3, 0), ApkVariant("x86_64")))
            runCurrent()

            assertNull(relaunched.value.available)
            assertNull(store.knownUpdate)
            assertEquals(1, downloader.cleared)
        }

    // ---- Later ----

    @Test
    fun `Later hides the banner for 7 days`() =
        runTest {
            val c = controller()
            c.onShown()
            runCurrent()

            c.later()
            assertFalse(c.value.bannerVisible)

            clock += UpdatePolicy.SNOOZE_MILLIS - 1
            assertFalse(controller().value.bannerVisible)

            clock += 1
            assertTrue(controller().value.bannerVisible)
        }

    @Test
    fun `Later does not hide the next version`() =
        runTest {
            val c = controller()
            c.onShown()
            runCurrent()
            c.later()

            releases.release = parsed(release("0.3.1"))
            c.checkNow()
            runCurrent()

            assertTrue(c.value.bannerVisible)
            assertEquals(UpdateVersion(0, 3, 1), c.value.available?.version)
        }

    @Test
    fun `after Later the settings still offer the update`() =
        runTest {
            val c = controller()
            c.onShown()
            runCurrent()
            c.later()

            assertTrue(c.value.snoozed)
            assertNotNull(c.value.available)
        }

    // ---- download, verify, install ----

    private suspend fun TestScope.readyController(): UpdateController {
        val c = controller()
        c.onShown()
        runCurrent()
        c.requestUpdate()
        assertTrue(c.value.confirming)
        return c
    }

    @Test
    fun `confirming downloads, verifies and installs`() =
        runTest {
            val c = readyController()

            c.confirmUpdate()
            assertFalse(c.value.confirming)
            assertIs<UpdatePhase.Downloading>(c.value.phase)
            runCurrent()

            assertEquals(UpdatePhase.Installing(), c.value.phase)
            assertEquals(listOf("Brainiac-0.3.0-x86_64.apk"), installer.installed.map { it.name })
        }

    @Test
    fun `Android's confirmation is handed out once, then the result is shown`() =
        runTest {
            val c = readyController()
            c.confirmUpdate()
            runCurrent()
            val confirm = Intent(Intent.ACTION_VIEW)

            events.emit(InstallEvent.NeedsConfirmation(confirm))
            runCurrent()
            assertIs<UpdatePhase.AwaitingConfirmation>(c.value.phase)

            assertSame(confirm, c.takeConfirmationIntent())
            assertNull(c.takeConfirmationIntent())
            assertEquals(UpdatePhase.Installing(confirmationShown = true), c.value.phase)

            events.emit(InstallEvent.Succeeded)
            runCurrent()
            assertEquals(UpdatePhase.Succeeded, c.value.phase)
            assertNull(store.knownUpdate)
        }

    @Test
    fun `a declined or failed installation is shown and can be retried`() =
        runTest {
            val c = readyController()
            c.confirmUpdate()
            runCurrent()

            events.emit(InstallEvent.Canceled)
            runCurrent()
            assertEquals(UpdatePhase.Canceled, c.value.phase)
            assertTrue(c.value.bannerVisible)

            c.requestUpdate()
            c.confirmUpdate()
            runCurrent()
            events.emit(InstallEvent.Failed(PackageInstaller.STATUS_FAILURE_STORAGE, "no space"))
            runCurrent()
            assertEquals(
                UpdatePhase.Failed(UpdateFailure.InstallFailed(PackageInstaller.STATUS_FAILURE_STORAGE, "no space")),
                c.value.phase,
            )
            assertEquals(2, installer.installed.size)
        }

    @Test
    fun `an apk that fails verification is discarded, not installed`() =
        runTest {
            listOf(
                ApkIdentity("nl.remcovolmer.brainiac", 400_003_000, setOf("other key")) to UpdateFailure.WrongSignature,
                ApkIdentity("com.ichi2.anki", 400_003_000, setOf("key")) to UpdateFailure.WrongPackage,
                ApkIdentity("nl.remcovolmer.brainiac", 400_002_000, setOf("key")) to UpdateFailure.NotNewer,
                null to UpdateFailure.NotAnApk,
            ).forEach { (archive, failure) ->
                inspector.archive = archive
                val c = readyController()
                c.confirmUpdate()
                runCurrent()

                assertEquals(UpdatePhase.Failed(failure), c.value.phase)
                assertTrue(installer.installed.isEmpty())
                assertEquals("Brainiac-0.3.0-x86_64.apk", downloader.discarded.last().name)
            }
        }

    @Test
    fun `a failed download is shown`() =
        runTest {
            downloader.failure = UpdateFailure.DigestMismatch
            val c = readyController()

            c.confirmUpdate()
            runCurrent()

            assertEquals(UpdatePhase.Failed(UpdateFailure.DigestMismatch), c.value.phase)
        }

    @Test
    fun `without the install permission the user is asked first`() =
        runTest {
            installer.allowed = false
            val c = readyController()
            c.confirmUpdate()
            runCurrent()
            assertEquals(UpdatePhase.NeedsPermission(), c.value.phase)
            assertTrue(installer.installed.isEmpty())

            c.permissionSettingsOpened()
            assertEquals(UpdatePhase.NeedsPermission(settingsOpen = true), c.value.phase)

            // came back without allowing it
            c.onPermissionSettingsClosed()
            assertEquals(UpdatePhase.Failed(UpdateFailure.PermissionDenied), c.value.phase)
        }

    @Test
    fun `after allowing the permission the installation goes on`() =
        runTest {
            installer.allowed = false
            val c = readyController()
            c.confirmUpdate()
            runCurrent()
            c.permissionSettingsOpened()

            installer.allowed = true
            c.onPermissionSettingsClosed()
            runCurrent()

            assertEquals(UpdatePhase.Installing(), c.value.phase)
            assertEquals(1, installer.installed.size)
        }

    @Test
    fun `while an update runs no check replaces it`() =
        runTest {
            installer.allowed = false
            val c = readyController()
            c.confirmUpdate()
            runCurrent()

            releases.release = parsed(release("0.4.0"))
            c.checkNow()
            runCurrent()

            assertEquals(UpdateVersion(0, 3, 0), c.value.available?.version)
            assertEquals(1, releases.calls)
        }

    @Test
    fun `a stalled confirmation can be dismissed`() =
        runTest {
            val c = readyController()
            c.confirmUpdate()
            runCurrent()
            events.emit(InstallEvent.NeedsConfirmation(Intent(Intent.ACTION_VIEW)))
            runCurrent()
            c.takeConfirmationIntent()

            c.dismissResult()

            assertEquals(UpdatePhase.Idle, c.value.phase)
            assertTrue(c.value.bannerVisible)
        }

    @Test
    fun `install results map from PackageInstaller statuses`() {
        val intent = Intent(Intent.ACTION_VIEW)
        assertEquals(InstallEvent.NeedsConfirmation(intent), installEventFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null, intent))
        assertEquals(
            InstallEvent.Failed(PackageInstaller.STATUS_PENDING_USER_ACTION, null),
            installEventFor(PackageInstaller.STATUS_PENDING_USER_ACTION, null, null),
        )
        assertEquals(InstallEvent.Succeeded, installEventFor(PackageInstaller.STATUS_SUCCESS, null, null))
        assertEquals(InstallEvent.Canceled, installEventFor(PackageInstaller.STATUS_FAILURE_ABORTED, "aborted", null))
        assertEquals(
            InstallEvent.Failed(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE"),
            installEventFor(PackageInstaller.STATUS_FAILURE_CONFLICT, "INSTALL_FAILED_UPDATE_INCOMPATIBLE", null),
        )
    }
}
