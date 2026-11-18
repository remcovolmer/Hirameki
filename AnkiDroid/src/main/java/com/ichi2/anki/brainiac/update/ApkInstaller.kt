// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.IntentCompat
import com.ichi2.anki.ioDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/** What PackageInstaller reports about a committed session. */
sealed interface InstallEvent {
    /** Android wants the user to confirm; [intent] opens its dialog. */
    data class NeedsConfirmation(
        val intent: Intent,
    ) : InstallEvent

    data object Succeeded : InstallEvent

    /** The user declined in Android's dialog. */
    data object Canceled : InstallEvent

    data class Failed(
        val status: Int,
        val message: String?,
    ) : InstallEvent
}

/** Maps the `EXTRA_STATUS` of a PackageInstaller result to an [InstallEvent]. */
fun installEventFor(
    status: Int,
    message: String?,
    confirmIntent: Intent?,
): InstallEvent =
    when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION ->
            confirmIntent?.let { InstallEvent.NeedsConfirmation(it) } ?: InstallEvent.Failed(status, message)
        PackageInstaller.STATUS_SUCCESS -> InstallEvent.Succeeded
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvent.Canceled
        else -> InstallEvent.Failed(status, message)
    }

/** Carries results from [UpdateInstallReceiver] to the [UpdateController] of this process. */
object InstallEvents {
    private val mutableEvents = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<InstallEvent> = mutableEvents.asSharedFlow()

    fun post(event: InstallEvent) {
        if (!mutableEvents.tryEmit(event)) Timber.w("Update: dropped install event %s", event)
    }
}

/** Receives the PackageInstaller result of an update session (not exported; only the system's PendingIntent reaches it). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Timber.i("Update: install status %d (%s)", status, message)
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        InstallEvents.post(installEventFor(status, message, confirm))
    }
}

interface ApkInstaller {
    /** Whether the user allows this app to install apps ("install unknown apps"). */
    fun canInstallPackages(): Boolean

    /**
     * Writes [file] into a new PackageInstaller session and commits it. The outcome arrives as an
     * [InstallEvent] on [InstallEvents].
     *
     * @throws UpdateException
     */
    suspend fun install(file: File)
}

/** [ApkInstaller] over the PackageInstaller session API. */
class PackageInstallerApkInstaller(
    context: Context,
    private val dispatcher: CoroutineDispatcher = ioDispatcher,
) : ApkInstaller {
    private val context = context.applicationContext

    override fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(file: File) =
        withContext(dispatcher) {
            val installer = context.packageManager.packageInstaller
            // a session of an earlier attempt that never finished (e.g. the dialog was dismissed)
            installer.mySessions.forEach { abandonQuietly(installer, it.sessionId) }
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                    }
                }
            val sessionId =
                try {
                    installer.createSession(params)
                } catch (e: IOException) {
                    throw UpdateException(UpdateFailure.InstallFailed(PackageInstaller.STATUS_FAILURE, e.message), e)
                }
            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite("brainiac.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                    // mutable: the system adds the status extras to it
                    val pending =
                        PendingIntent.getBroadcast(
                            context,
                            sessionId,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    session.commit(pending.intentSender)
                }
            } catch (e: IOException) {
                abandonQuietly(installer, sessionId)
                throw UpdateException(UpdateFailure.InstallFailed(PackageInstaller.STATUS_FAILURE, e.message), e)
            } catch (e: SecurityException) {
                abandonQuietly(installer, sessionId)
                throw UpdateException(UpdateFailure.InstallFailed(PackageInstaller.STATUS_FAILURE, e.message), e)
            }
        }

    private fun abandonQuietly(
        installer: PackageInstaller,
        sessionId: Int,
    ) {
        try {
            installer.abandonSession(sessionId)
        } catch (e: SecurityException) {
            Timber.d(e, "Update: session %d already gone", sessionId)
        }
    }
}
