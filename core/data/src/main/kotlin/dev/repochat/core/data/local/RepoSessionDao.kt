package dev.repochat.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RepoSessionDao {

    @Query("SELECT * FROM repo_sessions WHERE repoKey = :repoKey")
    fun observe(repoKey: String): Flow<RepoSessionEntity?>

    @Query("SELECT * FROM repo_sessions WHERE repoKey = :repoKey")
    suspend fun get(repoKey: String): RepoSessionEntity?

    @Query("SELECT * FROM repo_sessions ORDER BY updated_at DESC, repoKey ASC")
    fun observeAll(): Flow<List<RepoSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RepoSessionEntity)

    @Query("UPDATE repo_sessions SET workingBranch = :branch WHERE repoKey = :repoKey")
    suspend fun updateBranch(repoKey: String, branch: String)

    @Query("UPDATE repo_sessions SET title = :title WHERE repoKey = :repoKey")
    suspend fun updateTitle(repoKey: String, title: String)

    @Query("UPDATE repo_sessions SET updated_at = :updatedAt WHERE repoKey = :repoKey")
    suspend fun touch(repoKey: String, updatedAt: Long)

    @Query("DELETE FROM repo_sessions WHERE repoKey = :repoKey")
    suspend fun delete(repoKey: String)
}
