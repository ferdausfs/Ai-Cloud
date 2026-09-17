package dev.repochat.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room row for one installed agent skill (Claude Code SKILL.md compatible).
 * The normalized skill name is the primary key — re-installing a skill with
 * the same name updates it in place.
 */
@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val name: String,
    val description: String,
    val instructions: String,
    val sourceRepo: String,
    val sourcePath: String,
    val license: String?,
    val allowedTools: String?,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
)

@Dao
interface SkillDao {

    @Query("SELECT * FROM skills ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills WHERE enabled = 1 ORDER BY installedAt DESC")
    suspend fun enabled(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE name = :name")
    suspend fun byName(name: String): SkillEntity?

    @Query("SELECT * FROM skills")
    suspend fun all(): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("UPDATE skills SET enabled = :enabled WHERE name = :name")
    suspend fun setEnabled(name: String, enabled: Boolean)

    @Query("DELETE FROM skills WHERE name = :name")
    suspend fun delete(name: String)
}
