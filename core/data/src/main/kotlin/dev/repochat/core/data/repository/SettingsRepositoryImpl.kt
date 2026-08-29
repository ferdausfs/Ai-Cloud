package dev.repochat.core.data.repository

import dev.repochat.core.data.local.EncryptedSettingsStore
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.AppSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    store: EncryptedSettingsStore,
) : SettingsRepository {

    private val cache = MutableStateFlow(store.read())

    override val settings: Flow<AppSettings> = cache

    /** Non-suspending accessor used by the OkHttp auth interceptors. */
    override fun cached(): AppSettings = cache.value

    override suspend fun current(): AppSettings = cache.value

    override suspend fun save(settings: AppSettings) {
        cache.value = settings
        store.write(settings)
    }
}
