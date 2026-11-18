// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.brainiac.update

import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class UpdateStoreTest {
    private val prefs = updatePreferences(ApplicationProvider.getApplicationContext())
    private val store = SharedPreferencesUpdateStore(prefs)

    @Test
    fun `defaults are automatic check on, never checked, nothing known`() {
        assertTrue(store.autoCheckEnabled)
        assertEquals(0L, store.lastCheckMillis)
        assertNull(store.snoozedVersion)
        assertNull(store.knownUpdate)
    }

    @Test
    fun `values survive a new store on the same file`() {
        val update =
            AvailableUpdate(UpdateVersion(0, 3, 0), "notes", ReleaseAsset("Brainiac-0.3.0-universal.apk", 10, "https://github.com/x", "ab"))
        store.autoCheckEnabled = false
        store.lastCheckMillis = 123
        store.snoozedVersion = UpdateVersion(0, 3, 0)
        store.snoozedAtMillis = 456
        store.knownUpdate = update

        val reopened = SharedPreferencesUpdateStore(updatePreferences(ApplicationProvider.getApplicationContext()))
        assertFalse(reopened.autoCheckEnabled)
        assertEquals(123L, reopened.lastCheckMillis)
        assertEquals(UpdateVersion(0, 3, 0), reopened.snoozedVersion)
        assertEquals(456L, reopened.snoozedAtMillis)
        assertEquals(update, reopened.knownUpdate)

        store.knownUpdate = null
        assertNull(reopened.knownUpdate)
    }

    @Test
    fun `an unreadable stored update is dropped`() {
        prefs.edit { putString("known_update", "{not json") }

        assertNull(store.knownUpdate)
    }

    @Test
    fun `the policy is 24 hours between automatic checks, 7 days of Later`() {
        val memory = MemoryUpdateStore()
        val day = UpdatePolicy.AUTO_CHECK_INTERVAL_MILLIS
        assertTrue(UpdatePolicy.autoCheckDue(memory, 5 * day))
        memory.lastCheckMillis = 5 * day
        assertFalse(UpdatePolicy.autoCheckDue(memory, 6 * day - 1))
        assertTrue(UpdatePolicy.autoCheckDue(memory, 6 * day))
        // a clock that went back
        assertTrue(UpdatePolicy.autoCheckDue(memory, 4 * day))
        memory.autoCheckEnabled = false
        assertFalse(UpdatePolicy.autoCheckDue(memory, 10 * day))

        val v = UpdateVersion(0, 3, 0)
        memory.snoozedVersion = v
        memory.snoozedAtMillis = 0
        assertTrue(UpdatePolicy.isSnoozed(memory, v, UpdatePolicy.SNOOZE_MILLIS - 1))
        assertFalse(UpdatePolicy.isSnoozed(memory, v, UpdatePolicy.SNOOZE_MILLIS))
        assertFalse(UpdatePolicy.isSnoozed(memory, UpdateVersion(0, 3, 1), 1))
    }
}
