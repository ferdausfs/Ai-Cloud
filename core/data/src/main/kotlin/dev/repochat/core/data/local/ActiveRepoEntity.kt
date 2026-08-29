package dev.repochat.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Single-row table remembering the repository the user is working with. */
@Entity(tableName = "active_repo")
data class ActiveRepoEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "repo_key") val repoKey: String,
    val owner: String,
    val repo: String,
    @ColumnInfo(name = "default_branch") val defaultBranch: String,
    @ColumnInfo(name = "selected_at") val selectedAt: Long,
)
