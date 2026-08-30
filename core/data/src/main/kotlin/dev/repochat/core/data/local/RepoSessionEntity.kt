package dev.repochat.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per conversation. [repoKey] is `owner/repo` for repo chats or
 * `general/{sessionId}` for general chats. [sessionId] also names the
 * per-session working branch (`ai-chat/{sessionId}`) for repo mode.
 */
@Entity(tableName = "repo_sessions")
data class RepoSessionEntity(
    @PrimaryKey val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val sessionId: String,
    val workingBranch: String?,
    /** GENERAL or REPO — default REPO preserves pre-v2 rows after migration. */
    val mode: String = "REPO",
    val title: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
