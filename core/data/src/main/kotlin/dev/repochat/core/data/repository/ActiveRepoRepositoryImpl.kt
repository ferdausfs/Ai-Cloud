package dev.repochat.core.data.repository

import dev.repochat.core.data.local.ActiveRepoDao
import dev.repochat.core.data.local.toEntity
import dev.repochat.core.data.local.toModel
import dev.repochat.core.domain.ActiveRepoRepository
import dev.repochat.core.model.ActiveRepo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ActiveRepoRepositoryImpl @Inject constructor(
    private val dao: ActiveRepoDao,
) : ActiveRepoRepository {

    override val active: Flow<ActiveRepo?> = dao.observe().map { it?.toModel() }

    override suspend fun set(repo: ActiveRepo) {
        dao.upsert(repo.toEntity())
    }

    override suspend fun clear() {
        dao.clear()
    }
}
