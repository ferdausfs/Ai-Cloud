package dev.repochat.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {

    @Query(
        "SELECT * FROM chat_messages WHERE repoKey = :repoKey AND sessionId = :sessionId " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observe(repoKey: String, sessionId: String): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE repoKey = :repoKey AND sessionId = :sessionId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun recent(repoKey: String, sessionId: String, limit: Int): List<ChatMessageEntity>

    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("UPDATE chat_messages SET status = :status, base64Sha = :newSha WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, newSha: String?)

    @Query("DELETE FROM chat_messages WHERE repoKey = :repoKey AND sessionId = :sessionId")
    suspend fun clear(repoKey: String, sessionId: String)
}
