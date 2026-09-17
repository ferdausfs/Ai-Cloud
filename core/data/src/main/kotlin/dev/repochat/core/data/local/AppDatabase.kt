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
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoSessionDao(): RepoSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun activeRepoDao(): ActiveRepoDao
    abstract fun skillDao(): SkillDao

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
    }
}
