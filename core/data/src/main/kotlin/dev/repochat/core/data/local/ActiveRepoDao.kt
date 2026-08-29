package dev.repochat.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveRepoDao {

    @Query("SELECT * FROM active_repo WHERE id = 1")
    fun observe(): Flow<ActiveRepoEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(repo: ActiveRepoEntity)

    @Query("DELETE FROM active_repo")
    suspend fun clear()
}
