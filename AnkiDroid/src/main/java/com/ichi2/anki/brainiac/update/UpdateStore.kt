// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import timber.log.Timber

/** What the updater remembers between launches. */
interface UpdateStore {
    /** The switch in the settings; on by default. */
    var autoCheckEnabled: Boolean

    /** When GitHub last answered a check (automatic or manual); 0 when never. */
    var lastCheckMillis: Long

    /** The version the user chose "Later" for, and when. */
    var snoozedVersion: UpdateVersion?
    var snoozedAtMillis: Long

    /** The update found by the last check, so the banner survives a restart without a new request. */
    var knownUpdate: AvailableUpdate?
}

object UpdatePolicy {
    const val AUTO_CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    const val SNOOZE_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** At most one automatic check per 24 hours; a clock that went back counts as due. */
    fun autoCheckDue(
        store: UpdateStore,
        now: Long,
    ): Boolean = store.autoCheckEnabled && (now - store.lastCheckMillis) !in 0 until AUTO_CHECK_INTERVAL_MILLIS

    /** "Later" hides [version] for 7 days; a newer version shows again at once. */
    fun isSnoozed(
        store: UpdateStore,
        version: UpdateVersion,
        now: Long,
    ): Boolean = store.snoozedVersion == version && (now - store.snoozedAtMillis) in 0 until SNOOZE_MILLIS
}

/** Its own preferences file, so it never collides with (or gets reset together with) the app's preferences. */
private const val PREFS_NAME = "brainiac_update"
private const val KEY_AUTO_CHECK = "auto_check"
private const val KEY_LAST_CHECK = "last_check"
private const val KEY_SNOOZED_VERSION = "snoozed_version"
private const val KEY_SNOOZED_AT = "snoozed_at"
private const val KEY_KNOWN_UPDATE = "known_update"

fun updatePreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

class SharedPreferencesUpdateStore(
    private val prefs: SharedPreferences,
) : UpdateStore {
    override var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    override var lastCheckMillis: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_CHECK, value) }

    override var snoozedVersion: UpdateVersion?
        get() = prefs.getString(KEY_SNOOZED_VERSION, null)?.let(UpdateVersion::parse)
        set(value) = prefs.edit { putString(KEY_SNOOZED_VERSION, value?.toString()) }

    override var snoozedAtMillis: Long
        get() = prefs.getLong(KEY_SNOOZED_AT, 0)
        set(value) = prefs.edit { putLong(KEY_SNOOZED_AT, value) }

    override var knownUpdate: AvailableUpdate?
        get() = prefs.getString(KEY_KNOWN_UPDATE, null)?.let { decode(it, AvailableUpdate.serializer()) }
        set(value) =
            prefs.edit {
                putString(KEY_KNOWN_UPDATE, value?.let { updateJson.encodeToString(AvailableUpdate.serializer(), it) })
            }

    private fun <T> decode(
        value: String,
        serializer: KSerializer<T>,
    ): T? =
        try {
            updateJson.decodeFromString(serializer, value)
        } catch (e: SerializationException) {
            Timber.w(e, "Update: dropping unreadable stored update")
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Update: dropping unreadable stored update")
            null
        }
}
