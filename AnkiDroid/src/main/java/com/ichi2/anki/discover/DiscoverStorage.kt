// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.discover

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import timber.log.Timber

/** Server URL and app token, entered by the user on the Discover settings page. */
interface DiscoverSettings {
    val serverUrl: String
    val token: String

    fun save(
        serverUrl: String,
        token: String,
    )
}

/** Why the service refused the report of a note that already exists locally. */
@Serializable
enum class OrphanReason {
    /** 409 `proposal_not_new`: the proposal was decided elsewhere (or a 409 without code). */
    PROPOSAL_NOT_NEW,

    /** 409 `note_id_mismatch`: the proposal was approved with another note. */
    NOTE_ID_MISMATCH,

    /** 404: the proposal no longer exists. */
    NOT_FOUND,
}

/**
 * A "yes" that the service has not acknowledged yet.
 *
 * - [noteId] null: a marker written just before the note is created. If the app dies before the id
 *   is stored, the next attempt looks for that note instead of creating a second one.
 * - [noteId] set, [orphanReason] null: the note exists, the report is retried with the same id.
 * - [orphanReason] set: the service refused the report. The note stays in the collection without a
 *   link; it is shown to the user and never reported again.
 */
@Serializable
data class PendingApproval(
    val proposalId: Long,
    val noteId: Long? = null,
    /** The fields as the user edited them, or null when the proposal was accepted unchanged. */
    val editedFields: Map<String, String>? = null,
    val orphanReason: OrphanReason? = null,
) {
    val awaitingReport: Boolean get() = noteId != null && orphanReason == null
    val isOrphan: Boolean get() = noteId != null && orphanReason != null
}

interface PendingApprovalStore {
    fun get(proposalId: Long): PendingApproval?

    fun all(): List<PendingApproval>

    fun put(approval: PendingApproval)

    fun remove(proposalId: Long)
}

/**
 * Both stores live in their own preferences file, so they never collide with (or get reset
 * together with) the app's own preferences.
 */
private const val PREFS_NAME = "brainiac_discover"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_TOKEN = "app_token"
private const val PENDING_PREFIX = "pending_approval_"

fun discoverPreferences(context: Context): SharedPreferences =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

class SharedPreferencesDiscoverSettings(
    private val prefs: SharedPreferences,
) : DiscoverSettings {
    override val serverUrl: String get() = prefs.getString(KEY_SERVER_URL, null).orEmpty()
    override val token: String get() = prefs.getString(KEY_TOKEN, null).orEmpty()

    override fun save(
        serverUrl: String,
        token: String,
    ) {
        prefs.edit {
            putString(KEY_SERVER_URL, serverUrl.trim())
            putString(KEY_TOKEN, token.trim())
        }
    }
}

class SharedPreferencesPendingApprovalStore(
    private val prefs: SharedPreferences,
) : PendingApprovalStore {
    override fun get(proposalId: Long): PendingApproval? = prefs.getString(key(proposalId), null)?.let(::decode)

    override fun all(): List<PendingApproval> =
        prefs.all
            .filterKeys { it.startsWith(PENDING_PREFIX) }
            .values
            .mapNotNull { (it as? String)?.let(::decode) }

    // commit, not apply: the mapping must be on disk before the service is called, otherwise a
    // crash in between would lead to a second note on the next attempt
    override fun put(approval: PendingApproval) {
        prefs.edit(commit = true) {
            putString(key(approval.proposalId), discoverJson.encodeToString(PendingApproval.serializer(), approval))
        }
    }

    override fun remove(proposalId: Long) {
        prefs.edit(commit = true) { remove(key(proposalId)) }
    }

    private fun key(proposalId: Long) = "$PENDING_PREFIX$proposalId"

    private fun decode(value: String): PendingApproval? =
        try {
            discoverJson.decodeFromString(PendingApproval.serializer(), value)
        } catch (e: SerializationException) {
            Timber.w(e, "Discover: dropping unreadable pending approval")
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Discover: dropping unreadable pending approval")
            null
        }
}
