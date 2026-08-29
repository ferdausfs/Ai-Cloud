package dev.repochat.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RepoSessionEntity::class,
        ChatMessageEntity::class,
        ActiveRepoEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoSessionDao(): RepoSessionDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun activeRepoDao(): ActiveRepoDao
}
