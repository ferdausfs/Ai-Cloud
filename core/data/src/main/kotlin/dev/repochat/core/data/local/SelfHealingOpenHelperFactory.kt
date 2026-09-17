package dev.repochat.core.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Last-resort recovery wrapper around the platform open helper.
 *
 * Room throws IllegalStateException from getWritableDatabase in exactly two
 * permanent, per-launch-recurring failure modes:
 *  1. "Migration didn't properly handle ..." — migrated schema ≠ entity schema;
 *  2. "Room cannot verify the data integrity" — identity-hash mismatch.
 *
 * Both used to crash-loop the app on every launch (the field-reported failure
 * class). Correct migrations remain the FIRST line of defense; this wrapper
 * only fires if validation still fails for an unforeseen reason: it deletes
 * the broken database file and lets Room recreate it — the app opens (chat
 * history is lost, settings/keys live in [EncryptedSettingsStore] and are
 * kept). Anything else (SQLiteBusyException, disk errors, corruption handled
 * by the framework) propagates untouched.
 */
class SelfHealingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val inner = delegate.create(configuration)
        val context: Context = configuration.context
        val name: String? = configuration.name
        return object : SupportSQLiteOpenHelper by inner {
            override val writableDatabase: SupportSQLiteDatabase
                get() = openHealed()

            override val readableDatabase: SupportSQLiteDatabase
                get() = openHealed()

            private fun openHealed(): SupportSQLiteDatabase = try {
                inner.writableDatabase
            } catch (broken: IllegalStateException) {
                // Permanent schema/migration failure — recover destructively.
                if (!name.isNullOrBlank()) {
                    context.deleteDatabase(name)
                }
                inner.writableDatabase
            }
        }
    }
}
