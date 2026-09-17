package dev.repochat.core.domain

import dev.repochat.core.model.UsageEvent
import dev.repochat.core.model.UsageProviderTotals
import dev.repochat.core.model.UsageTotals

/**
 * Metered provider-call history backing the usage dashboard and the daily
 * token budget. Append-only ledger; aggregation happens at read time.
 */
interface UsageRepository {

    /** Persists one metered call. */
    suspend fun record(event: UsageEvent)

    /** Aggregated totals for all calls at or after [sinceMillis]. */
    suspend fun totalsSince(sinceMillis: Long): UsageTotals

    /** Per-provider breakdown for all calls at or after [sinceMillis]. */
    suspend fun perProviderSince(sinceMillis: Long): List<UsageProviderTotals>

    /** Chronological events for CSV export (oldest first). */
    suspend fun eventsForExport(limit: Int = 50_000): List<UsageEvent>

    /** Deletes the whole ledger (Settings "Clear usage data"). */
    suspend fun clearAll()
}
