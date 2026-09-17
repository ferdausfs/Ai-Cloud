package dev.repochat.core.data.repository

import dev.repochat.core.data.local.UsageDao
import dev.repochat.core.data.local.UsageEventEntity
import dev.repochat.core.domain.UsageRepository
import dev.repochat.core.model.UsageEvent
import dev.repochat.core.model.UsageProviderTotals
import dev.repochat.core.model.UsageTotals
import javax.inject.Inject
import javax.inject.Singleton

/** Room-backed append-only usage ledger. */
@Singleton
class UsageRepositoryImpl @Inject constructor(
    private val dao: UsageDao,
) : UsageRepository {

    override suspend fun record(event: UsageEvent) {
        dao.insert(UsageEventEntity.from(event))
    }

    override suspend fun totalsSince(sinceMillis: Long): UsageTotals =
        dao.totalsSince(sinceMillis).toModel()

    override suspend fun perProviderSince(sinceMillis: Long): List<UsageProviderTotals> =
        dao.perProviderSince(sinceMillis).map { it.toModel() }

    override suspend fun eventsForExport(limit: Int): List<UsageEvent> =
        dao.eventsForExport(limit).map { it.toModel() }

    override suspend fun clearAll() {
        dao.clearAll()
    }
}
