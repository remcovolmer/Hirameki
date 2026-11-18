// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.content.Context
import android.content.Intent
import com.ichi2.anki.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/** The installed app as the updater sees it. [version] or [variant] null: not recognised, so no updates. */
data class InstalledApp(
    val versionName: String,
    val version: UpdateVersion?,
    val variant: ApkVariant?,
) {
    companion object {
        fun from(
            versionName: String?,
            versionCode: Long,
            abiCodes: Map<Int, String>,
        ) = InstalledApp(
            versionName = versionName.orEmpty(),
            version = UpdateVersion.fromVersionName(versionName),
            variant = ApkVariant.fromVersionCode(versionCode, abiCodes),
        )
    }
}

/** Where the download and installation of an update stand. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase

    data class Downloading(
        val bytes: Long,
        val total: Long,
    ) : UpdatePhase {
        val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    data object Verifying : UpdatePhase

    /** The apk is checked, but the user has not allowed this app to install apps. */
    data class NeedsPermission(
        val settingsOpen: Boolean = false,
    ) : UpdatePhase

    /** Waiting for PackageInstaller; [confirmationShown]: its dialog was opened, the result is pending. */
    data class Installing(
        val confirmationShown: Boolean = false,
    ) : UpdatePhase

    /** Android's confirmation dialog has to be opened by the UI, see [UpdateController.takeConfirmationIntent]. */
    data class AwaitingConfirmation(
        val intent: Intent,
    ) : UpdatePhase

    data object Succeeded : UpdatePhase

    data object Canceled : UpdatePhase

    data class Failed(
        val failure: UpdateFailure,
    ) : UpdatePhase
}

/** The result of the last manual check, shown in the settings row. Automatic checks leave it alone. */
sealed interface CheckStatus {
    data object Idle : CheckStatus

    data object Checking : CheckStatus

    data class UpToDate(
        val version: String,
    ) : CheckStatus

    data class Available(
        val version: UpdateVersion,
    ) : CheckStatus

    /** A newer version exists without an apk for this kind of build ([variant]). */
    data class NoMatchingAsset(
        val version: UpdateVersion,
        val variant: String,
    ) : CheckStatus

    data class Failed(
        val failure: UpdateFailure,
    ) : CheckStatus

    /** The installed versionName is not one of a release, so there is nothing to compare. */
    data object UnknownInstall : CheckStatus
}

data class UpdateUiState(
    /** False in builds that do not update themselves (the Play flavor): the UI shows only the version. */
    val supported: Boolean,
    val installedVersion: String,
    val autoCheckEnabled: Boolean,
    val check: CheckStatus = CheckStatus.Idle,
    val available: AvailableUpdate? = null,
    /** "Later" was chosen for [available]. */
    val snoozed: Boolean = false,
    /** The dialog with versions and release notes is open. */
    val confirming: Boolean = false,
    val phase: UpdatePhase = UpdatePhase.Idle,
) {
    /** A download or installation is running; no new check or update starts meanwhile. */
    val busy: Boolean
        get() =
            phase is UpdatePhase.Downloading ||
                phase is UpdatePhase.Verifying ||
                phase is UpdatePhase.Installing ||
                phase is UpdatePhase.AwaitingConfirmation ||
                phase is UpdatePhase.NeedsPermission

    val bannerVisible: Boolean get() = supported && available != null && (!snoozed || phase != UpdatePhase.Idle)
}

/**
 * Checks GitHub for a newer release, downloads its apk, verifies it and hands it to
 * PackageInstaller. One instance per process ([get]), so the banner and the settings row show the
 * same state and a download goes on when the screen that started it closes.
 */
class UpdateController(
    private val repo: String,
    private val installed: InstalledApp,
    private val supported: Boolean,
    private val store: UpdateStore,
    private val releases: ReleaseSource,
    private val downloader: ApkDownloader,
    private val inspector: ApkInspector,
    private val installer: ApkInstaller,
    installEvents: Flow<InstallEvent>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()

    private var checkJob: Job? = null
    private var updateJob: Job? = null

    /** The verified apk waiting for the install permission. */
    private var verifiedApk: File? = null

    init {
        scope.launch { installEvents.collect(::onInstallEvent) }
        if (store.knownUpdate != null && mutableState.value.available == null) {
            // installed meanwhile (or no longer newer): forget it and drop the download
            store.knownUpdate = null
            scope.launch { downloader.clear() }
        }
    }

    private fun initialState(): UpdateUiState {
        val known =
            store.knownUpdate?.takeIf { update ->
                supported && installed.version != null && update.version > installed.version
            }
        return UpdateUiState(
            supported = supported,
            installedVersion = installed.versionName,
            autoCheckEnabled = store.autoCheckEnabled,
            available = known,
            snoozed = known != null && UpdatePolicy.isSnoozed(store, known.version, now()),
        )
    }

    /** Called when the main screen is shown: checks automatically, at most once per 24 hours. */
    fun onShown() {
        if (!supported || checkJob?.isActive == true || mutableState.value.busy) return
        if (!UpdatePolicy.autoCheckDue(store, now())) return
        check(manual = false)
    }

    /** "Check for updates": always asks GitHub, and reports the outcome in [UpdateUiState.check]. */
    fun checkNow() {
        if (!supported || checkJob?.isActive == true) return
        if (mutableState.value.busy) return
        check(manual = true)
    }

    fun setAutoCheck(enabled: Boolean) {
        store.autoCheckEnabled = enabled
        mutableState.update { it.copy(autoCheckEnabled = enabled) }
    }

    /** "Later": hides the banner until a newer version appears or 7 days have passed. */
    fun later() {
        val update = mutableState.value.available ?: return
        if (mutableState.value.busy) return
        store.snoozedVersion = update.version
        store.snoozedAtMillis = now()
        mutableState.update { it.copy(snoozed = true, confirming = false, phase = UpdatePhase.Idle) }
    }

    /** "Update": opens the dialog with versions and release notes. */
    fun requestUpdate() {
        val state = mutableState.value
        if (state.available == null || state.busy) return
        mutableState.update { it.copy(confirming = true) }
    }

    fun dismissConfirmation() {
        mutableState.update { it.copy(confirming = false) }
    }

    /** Confirmed in the dialog: download, verify, then install. */
    fun confirmUpdate() {
        val update = mutableState.value.available ?: return
        if (mutableState.value.busy) return
        mutableState.update { it.copy(confirming = false, phase = UpdatePhase.Downloading(0, update.asset.size)) }
        updateJob =
            scope.launch {
                try {
                    var lastPercent = -1
                    val file =
                        downloader.download(update.asset) { bytes, total ->
                            val phase = UpdatePhase.Downloading(bytes, total)
                            if (phase.percent != lastPercent) {
                                lastPercent = phase.percent
                                mutableState.update { it.copy(phase = phase) }
                            }
                        }
                    mutableState.update { it.copy(phase = UpdatePhase.Verifying) }
                    val failure = verifyApk(inspector.archive(file), inspector.installed())
                    if (failure != null) {
                        Timber.w("Update: refused the download of %s: %s", update.version, failure)
                        downloader.discard(file)
                        throw UpdateException(failure)
                    }
                    verifiedApk = file
                    if (installer.canInstallPackages()) {
                        install(file)
                    } else {
                        mutableState.update { it.copy(phase = UpdatePhase.NeedsPermission()) }
                    }
                } catch (e: UpdateException) {
                    Timber.w(e, "Update: %s failed", update.version)
                    mutableState.update { it.copy(phase = UpdatePhase.Failed(e.failure)) }
                }
            }
    }

    /** Stops a running download; the partial file stays and the next attempt resumes it. */
    fun cancelDownload() {
        if (mutableState.value.phase !is UpdatePhase.Downloading) return
        updateJob?.cancel()
        mutableState.update { it.copy(phase = UpdatePhase.Idle) }
    }

    /** The UI opens Android's "install unknown apps" setting for this app. */
    fun permissionSettingsOpened() {
        mutableState.update {
            if (it.phase is UpdatePhase.NeedsPermission) it.copy(phase = UpdatePhase.NeedsPermission(settingsOpen = true)) else it
        }
    }

    /** Back from the setting: install when it is allowed now. */
    fun onPermissionSettingsClosed() {
        if (mutableState.value.phase !is UpdatePhase.NeedsPermission) return
        val file = verifiedApk
        when {
            !installer.canInstallPackages() ->
                mutableState.update { it.copy(phase = UpdatePhase.Failed(UpdateFailure.PermissionDenied)) }
            file == null || !file.isFile -> mutableState.update { it.copy(phase = UpdatePhase.Idle) }
            else ->
                scope.launch {
                    try {
                        install(file)
                    } catch (e: UpdateException) {
                        mutableState.update { it.copy(phase = UpdatePhase.Failed(e.failure)) }
                    }
                }
        }
    }

    /** The explanation of the permission was dismissed; the verified download stays for later. */
    fun cancelPermission() {
        if (mutableState.value.phase is UpdatePhase.NeedsPermission) mutableState.update { it.copy(phase = UpdatePhase.Idle) }
    }

    /**
     * The intent of Android's confirmation dialog, once: the phase moves on to [UpdatePhase.Installing],
     * so a second caller (or a recomposition) gets null and the dialog opens only once.
     */
    fun takeConfirmationIntent(): Intent? {
        val previous =
            mutableState.getAndUpdate {
                if (it.phase is UpdatePhase.AwaitingConfirmation) it.copy(phase = UpdatePhase.Installing(confirmationShown = true)) else it
            }
        return (previous.phase as? UpdatePhase.AwaitingConfirmation)?.intent
    }

    /**
     * Leaves a finished, failed or stalled attempt. Stalled: on some Android versions dismissing
     * the dialog reports nothing; the next attempt abandons that session.
     */
    fun dismissResult() {
        mutableState.update { state ->
            val phase = state.phase
            val done =
                phase is UpdatePhase.Failed ||
                    phase is UpdatePhase.Canceled ||
                    phase is UpdatePhase.Succeeded ||
                    (phase is UpdatePhase.Installing && phase.confirmationShown)
            if (done) state.copy(phase = UpdatePhase.Idle) else state
        }
    }

    private suspend fun install(file: File) {
        mutableState.update { it.copy(phase = UpdatePhase.Installing()) }
        installer.install(file)
    }

    private fun onInstallEvent(event: InstallEvent) {
        Timber.i("Update: %s", event)
        val phase =
            when (event) {
                is InstallEvent.NeedsConfirmation -> UpdatePhase.AwaitingConfirmation(event.intent)
                InstallEvent.Succeeded -> UpdatePhase.Succeeded
                InstallEvent.Canceled -> UpdatePhase.Canceled
                is InstallEvent.Failed -> UpdatePhase.Failed(UpdateFailure.InstallFailed(event.status, event.message))
            }
        if (event == InstallEvent.Succeeded) {
            store.knownUpdate = null
            scope.launch { downloader.clear() }
        }
        mutableState.update { it.copy(phase = phase) }
    }

    private fun check(manual: Boolean) {
        val installedVersion = installed.version
        if (installedVersion == null) {
            Timber.w("Update: installed version %s is not a release version", installed.versionName)
            if (manual) mutableState.update { it.copy(check = CheckStatus.UnknownInstall) }
            return
        }
        if (manual) mutableState.update { it.copy(check = CheckStatus.Checking) }
        checkJob =
            scope.launch {
                val outcome =
                    try {
                        evaluateRelease(releases.latestRelease(), repo, installedVersion, installed.variant)
                    } catch (e: UpdateException) {
                        // a network error never reached GitHub, so the next launch may try again
                        if (e.failure != UpdateFailure.Network) store.lastCheckMillis = now()
                        Timber.i("Update: check failed: %s", e.failure)
                        if (manual) mutableState.update { it.copy(check = CheckStatus.Failed(e.failure)) }
                        return@launch
                    }
                store.lastCheckMillis = now()
                applyOutcome(outcome, manual)
            }
    }

    private fun applyOutcome(
        outcome: ReleaseCheck,
        manual: Boolean,
    ) {
        // a download or installation that started meanwhile keeps its update
        if (mutableState.value.busy) return
        when (outcome) {
            is ReleaseCheck.Available -> {
                val update = outcome.update
                store.knownUpdate = update
                mutableState.update {
                    val changed = it.available != update
                    it.copy(
                        available = update,
                        snoozed = UpdatePolicy.isSnoozed(store, update.version, now()),
                        phase = if (changed) UpdatePhase.Idle else it.phase,
                        check = if (manual) CheckStatus.Available(update.version) else it.check,
                    )
                }
            }
            is ReleaseCheck.UpToDate -> {
                forgetUpdate()
                if (manual) mutableState.update { it.copy(check = CheckStatus.UpToDate(installed.versionName)) }
            }
            is ReleaseCheck.NoMatchingAsset -> {
                Timber.i("Update: %s has no apk for %s", outcome.version, installed.variant)
                forgetUpdate()
                if (manual) {
                    mutableState.update {
                        it.copy(check = CheckStatus.NoMatchingAsset(outcome.version, installed.variant?.assetSuffix ?: "?"))
                    }
                }
            }
        }
    }

    private fun forgetUpdate() {
        store.knownUpdate = null
        mutableState.update { it.copy(available = null, snoozed = false, phase = UpdatePhase.Idle) }
    }

    companion object {
        @Volatile
        private var instance: UpdateController? = null

        fun get(context: Context): UpdateController =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(app: Context): UpdateController {
            val info = installedPackageInfo(app)
            val repo = BuildConfig.BRAINIAC_RELEASES_REPO
            return UpdateController(
                repo = repo,
                installed =
                    InstalledApp.from(
                        info.versionName,
                        info.longVersionCode,
                        ApkVariant.parseAbiCodes(BuildConfig.BRAINIAC_ABI_VERSION_CODES),
                    ),
                supported = BuildConfig.BRAINIAC_UPDATER_ENABLED,
                store = SharedPreferencesUpdateStore(updatePreferences(app)),
                releases = GitHubReleaseSource(repo),
                downloader = OkHttpApkDownloader(File(app.cacheDir, "brainiac-update")),
                inspector = PackageManagerApkInspector(app),
                installer = PackageInstallerApkInstaller(app),
                installEvents = InstallEvents.events,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
