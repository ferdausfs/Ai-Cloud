package dev.repochat.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    /** 0/1 bounding check — true if the session has a committed write. */
    @Query(
        "SELECT COUNT(*) FROM chat_messages WHERE repo_key = :repoKey " +
            "AND session_id = :sessionId AND kind = 'WRITE_FILE' AND status = 'APPROVED' LIMIT 1",
    )
    suspend fun countApprovedWrites(repoKey: String, sessionId: String): Int

    @Query(
        "SELECT * FROM chat_messages WHERE repo_key = :repoKey AND session_id = :sessionId " +
            "ORDER BY created_at ASC, id ASC",
    )
    fun observe(repoKey: String, sessionId: String): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE repo_key = :repoKey AND session_id = :sessionId " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit",
    )
    suspend fun recent(repoKey: String, sessionId: String, limit: Int): List<ChatMessageEntity>

    @Query(
        "SELECT * FROM chat_messages WHERE repo_key = :repoKey " +
            "ORDER BY created_at DESC, id DESC LIMIT 1",
    )
    suspend fun latestForRepo(repoKey: String): ChatMessageEntity?

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET status = :status, base64_sha = :newSha WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, newSha: String?)

    @Query("DELETE FROM chat_messages WHERE repo_key = :repoKey AND session_id = :sessionId")
    suspend fun clear(repoKey: String, sessionId: String)

    @Query("DELETE FROM chat_messages WHERE repo_key = :repoKey")
    suspend fun clearAllForRepo(repoKey: String)
}
