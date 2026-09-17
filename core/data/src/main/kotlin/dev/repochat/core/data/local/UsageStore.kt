package dev.repochat.core.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.repochat.core.model.UsageKind
import dev.repochat.core.model.UsageProviderTotals
import dev.repochat.core.model.UsageTotals

/**
 * One metered provider call (chat completion, image generation, speech
 * synthesis). Append-only; the dashboard aggregates over time ranges.
 */
@Entity(tableName = "usage_events")
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis of the call. */
    @ColumnInfo(name = "ts") val timestamp: Long,
    /** Connection label, e.g. "Groq free tier". */
    @ColumnInfo(name = "provider") val provider: String,
    /** Model id used for the call (best effort). */
    @ColumnInfo(name = "model") val model: String,
    /** CHAT / IMAGE / SPEECH — see [UsageKind]. */
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    /** 1 = provider-reported counts, 0 = app-estimated. */
    @ColumnInfo(name = "reported") val reported: Int,
) {
    fun toModel(): dev.repochat.core.model.UsageEvent = dev.repochat.core.model.UsageEvent(
        timestampMillis = timestamp,
        provider = provider,
        model = model,
        kind = runCatching { UsageKind.valueOf(kind) }.getOrDefault(UsageKind.CHAT),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reported = reported == 1,
    )

    companion object {
        fun from(event: dev.repochat.core.model.UsageEvent): UsageEventEntity = UsageEventEntity(
            timestamp = event.timestampMillis,
            provider = event.provider,
            model = event.model,
            kind = event.kind.name,
            inputTokens = event.inputTokens,
            outputTokens = event.outputTokens,
            reported = if (event.reported) 1 else 0,
        )
    }
}

@Dao
interface UsageDao {

    @Insert
    suspend fun insert(event: UsageEventEntity)

    @Query(
        """
        SELECT COUNT(*) AS requests,
               IFNULL(SUM(input_tokens), 0) AS inputTokens,
               IFNULL(SUM(output_tokens), 0) AS outputTokens
        FROM usage_events WHERE ts >= :sinceMillis
        """,
    )
    suspend fun totalsSince(sinceMillis: Long): UsageTotalsRow

    @Query(
        """
        SELECT provider,
               COUNT(*) AS requests,
               IFNULL(SUM(input_tokens), 0) AS inputTokens,
               IFNULL(SUM(output_tokens), 0) AS outputTokens
        FROM usage_events WHERE ts >= :sinceMillis
        GROUP BY provider
        ORDER BY inputTokens + outputTokens DESC
        """,
    )
    suspend fun perProviderSince(sinceMillis: Long): List<UsageProviderRow>

    @Query("SELECT * FROM usage_events ORDER BY ts ASC LIMIT :limit")
    suspend fun eventsForExport(limit: Int): List<UsageEventEntity>

    @Query("DELETE FROM usage_events")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun count(): Int
}

/** Projection row for [UsageDao.totalsSince] — matches [UsageTotals]. */
data class UsageTotalsRow(
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    fun toModel() = UsageTotals(requests, inputTokens, outputTokens)
}

/** Projection row for [UsageDao.perProviderSince] — matches [UsageProviderTotals]. */
data class UsageProviderRow(
    val provider: String,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
) {
    fun toModel() = UsageProviderTotals(provider, requests, inputTokens, outputTokens)
}
