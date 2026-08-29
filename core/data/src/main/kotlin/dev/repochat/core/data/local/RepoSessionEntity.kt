package dev.repochat.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per repository the user chatted with. [sessionId] names the
 * per-session working branch (`ai-chat/{sessionId}`); [workingBranch] is
 * filled in once the branch is confirmed to exist on GitHub.
 */
@Entity(tableName = "repo_sessions")
data class RepoSessionEntity(
    @PrimaryKey val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val sessionId: String,
    val workingBranch: String?,
)
