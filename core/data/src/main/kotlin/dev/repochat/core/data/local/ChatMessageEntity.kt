package dev.repochat.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persisted chat message. Role/kind/status are stored as enum names. */
@Entity(
    tableName = "chat_messages",
    indices = [Index("repo_key"), Index("session_id")],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "repo_key") val repoKey: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val kind: String,
    val text: String? = null,
    @ColumnInfo(name = "file_path") val filePath: String? = null,
    @ColumnInfo(name = "base64_content") val base64Content: String? = null,
    @ColumnInfo(name = "base64_sha") val base64Sha: String? = null,
    @ColumnInfo(name = "commit_message") val commitMessage: String? = null,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
