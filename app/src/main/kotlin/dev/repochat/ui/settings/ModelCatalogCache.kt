package dev.repochat.ui.settings

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Last successfully loaded model catalog per connection id (plain
 * SharedPreferences — model ids are not secrets). Lets the model picker show
 * something useful offline / on API failure, with an "offline" indicator.
 */
@Singleton
class ModelCatalogCache @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Serializable
    data class Entry(
        val models: List<String>,
        val savedAtMillis: Long,
    )

    fun get(connectionId: String): Entry? {
        if (connectionId.isBlank()) return null
        val raw = prefs.getString(connectionId, null) ?: return null
        return try {
            json.decodeFromString(Entry.serializer(), raw)
        } catch (_: Exception) {
            null
        }
    }

    fun put(connectionId: String, models: List<String>) {
        if (connectionId.isBlank() || models.isEmpty()) return
        try {
            prefs.edit()
                .putString(
                    connectionId,
                    json.encodeToString(
                        Entry.serializer(),
                        Entry(models = models, savedAtMillis = System.currentTimeMillis()),
                    ),
                )
                .apply()
        } catch (_: Exception) {
            // Cache is best-effort only.
        }
    }

    fun remove(connectionId: String) {
        if (connectionId.isBlank()) return
        try {
            prefs.edit().remove(connectionId).apply()
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private companion object {
        const val FILE_NAME = "model_catalog_cache"
    }
}
