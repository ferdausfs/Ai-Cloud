package dev.repochat.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * EncryptedSharedPreferences-backed store for secrets (API keys, PAT), model
 * names, and multi-provider connection rows.
 */
@Suppress("DEPRECATION")
class EncryptedSettingsStore private constructor(
    private val prefs: SharedPreferences,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {

    fun read(): AppSettings {
        val ollamaKey = prefs.getString(KEY_OLLAMA_KEY, "").orEmpty()
        val modelName = prefs.getString(KEY_MODEL_NAME, "").orEmpty()
        val githubPat = prefs.getString(KEY_GITHUB_PAT, "").orEmpty()
        val connectionsRaw = prefs.getString(KEY_CONNECTIONS, null)
        var connections = if (connectionsRaw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString(ListSerializer(ServiceConnection.serializer()), connectionsRaw)
            } catch (_: Exception) {
                emptyList()
            }
        }
        if (connections.none { it.type == ConnectionType.OLLAMA } &&
            (ollamaKey.isNotBlank() || modelName.isNotBlank())
        ) {
            val id = prefs.getString(KEY_LEGACY_OLLAMA_ID, null)
                ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(KEY_LEGACY_OLLAMA_ID, it).apply()
                }
            connections = listOf(
                ServiceConnection(
                    id = id,
                    type = ConnectionType.OLLAMA,
                    label = "Ollama",
                    baseUrl = "https://ollama.com",
                    apiKey = ollamaKey,
                    modelName = modelName,
                ),
            ) + connections
        }
        val orderRaw = prefs.getString(KEY_PROVIDER_ORDER, null)
        val order = if (orderRaw.isNullOrBlank()) {
            connections.filter {
                it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE
            }.map { it.id }
        } else {
            orderRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val active = prefs.getString(KEY_ACTIVE_PROVIDER, null)?.takeIf { it.isNotBlank() }
        return AppSettings(
            ollamaKey = ollamaKey,
            modelName = modelName,
            githubPat = githubPat,
            connections = connections,
            providerOrder = order,
            activeProviderId = active,
        )
    }

    fun write(settings: AppSettings) {
        val connectionsJson = json.encodeToString(
            ListSerializer(ServiceConnection.serializer()),
            settings.connections,
        )
        val primaryOllama = settings.connections.firstOrNull { it.type == ConnectionType.OLLAMA }
        prefs.edit()
            .putString(KEY_OLLAMA_KEY, (primaryOllama?.apiKey ?: settings.ollamaKey).trim())
            .putString(KEY_MODEL_NAME, (primaryOllama?.modelName ?: settings.modelName).trim())
            .putString(KEY_GITHUB_PAT, settings.githubPat.trim())
            .putString(KEY_CONNECTIONS, connectionsJson)
            .putString(KEY_PROVIDER_ORDER, settings.providerOrder.joinToString(","))
            .putString(KEY_ACTIVE_PROVIDER, settings.activeProviderId.orEmpty())
            .apply()
    }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_OLLAMA_KEY = "ollama_api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_GITHUB_PAT = "github_pat"
        private const val KEY_CONNECTIONS = "connections_json"
        private const val KEY_PROVIDER_ORDER = "provider_order_csv"
        private const val KEY_ACTIVE_PROVIDER = "active_provider_id"
        private const val KEY_LEGACY_OLLAMA_ID = "legacy_ollama_connection_id"

        fun create(context: Context): EncryptedSettingsStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val prefs = EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return EncryptedSettingsStore(prefs)
        }
    }
}
