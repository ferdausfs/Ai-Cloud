package dev.repochat.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RepoSessionEntity::class,
        ChatMessageEntity::class,
        ActiveRepoEntity::class,
        SkillEntity::class,
        UsageEventEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoSessionDao(): RepoSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun activeRepoDao(): ActiveRepoDao
    abstract fun skillDao(): SkillDao
    abstract fun usageDao(): UsageDao

    companion object {
        /**
         * Additive: mode/title/updated_at on sessions. Existing repo chats stay
         * REPO mode; history is preserved (no wipe).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE repo_sessions ADD COLUMN mode TEXT NOT NULL DEFAULT 'REPO'",
                )
                db.execSQL("ALTER TABLE repo_sessions ADD COLUMN title TEXT")
                db.execSQL(
                    "ALTER TABLE repo_sessions ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0",
                )
                // Best-effort: stamp updated_at from latest message if any.
                db.execSQL(
                    """
                    UPDATE repo_sessions SET updated_at = IFNULL(
                      (SELECT MAX(created_at) FROM chat_messages
                       WHERE chat_messages.repo_key = repo_sessions.repoKey),
                      0
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Additive: skills table (installed agent skills). No data touched. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS skills (
                      name TEXT NOT NULL PRIMARY KEY,
                      description TEXT NOT NULL,
                      instructions TEXT NOT NULL,
                      sourceRepo TEXT NOT NULL,
                      sourcePath TEXT NOT NULL,
                      license TEXT,
                      allowedTools TEXT,
                      enabled INTEGER NOT NULL DEFAULT 1,
                      installedAt INTEGER NOT NULL DEFAULT 0,
                      updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /** Additive: usage_events metering table. No existing data touched. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS usage_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                      ts INTEGER NOT NULL,
                      provider TEXT NOT NULL,
                      model TEXT NOT NULL,
                      kind TEXT NOT NULL,
                      input_tokens INTEGER NOT NULL,
                      output_tokens INTEGER NOT NULL,
                      reported INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_ts ON usage_events(ts)",
                )
            }
        }

        /**
         * v4→v5: formalize the ts index in the entity schema. The v3→v4
         * migration created it via SQL while UsageEventEntity didn't declare
         * it — upgrade installs then failed Room's post-migration validation
         * (field startup crash, rolled back to v3 on every launch). Bumping
         * the version re-validates every existing v3/v4 database against the
         * now-declared index and refreshes the identity hash. Idempotent via
         * IF NOT EXISTS.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_events_ts ON usage_events(ts)",
                )
            }
        }
    }
}
