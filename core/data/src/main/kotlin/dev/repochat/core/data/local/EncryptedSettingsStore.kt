package dev.repochat.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * EncryptedSharedPreferences-backed store for secrets (API keys, PAT), model
 * names, and multi-provider connection rows.
 *
 * v2.5.2 HARDENING — this store is provided by a Hilt @Singleton that sits on
 * the first-screen composition path, so any exception here used to be an
 * instant app-crash on launch (the field-reported failure after the v2.5.0
 * update — e.g. a corrupted keyset or a device Keystore invalidated by an
 * update/restore). Now:
 *  - create() wipes the prefs file and retries once on any crypto failure;
 *  - if even the retry fails (broken Android Keystore on the device) it
 *    degrades to plain preferences — the app still opens, secrets are simply
 *    re-entered;
 *  - every prefs read/write is guarded, so one corrupted value degrades to
 *    its default instead of killing the process.
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
        val ollamaKey = pref("") { prefs.getString(KEY_OLLAMA_KEY, "") }.orEmpty()
        val modelName = pref("") { prefs.getString(KEY_MODEL_NAME, "") }.orEmpty()
        val githubPat = pref("") { prefs.getString(KEY_GITHUB_PAT, "") }.orEmpty()
        val connectionsRaw = pref<String?>(null) { prefs.getString(KEY_CONNECTIONS, null) }
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
            val id = pref<String?>(null) { prefs.getString(KEY_LEGACY_OLLAMA_ID, null) }
                ?: UUID.randomUUID().toString().also {
                    runCatching { prefs.edit().putString(KEY_LEGACY_OLLAMA_ID, it).apply() }
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
        val orderRaw = pref<String?>(null) { prefs.getString(KEY_PROVIDER_ORDER, null) }
        val order = if (orderRaw.isNullOrBlank()) {
            connections.filter {
                it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE
            }.map { it.id }
        } else {
            orderRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val active = pref<String?>(null) { prefs.getString(KEY_ACTIVE_PROVIDER, null) }?.takeIf { it.isNotBlank() }
        val budget = pref<String?>(null) { prefs.getString(KEY_DAILY_TOKEN_BUDGET, null) }
            ?.trim()?.toLongOrNull() ?: 0L
        return AppSettings(
            ollamaKey = ollamaKey,
            modelName = modelName,
            githubPat = githubPat,
            connections = connections,
            providerOrder = order,
            activeProviderId = active,
            dailyTokenBudget = budget.coerceAtLeast(0L),
        )
    }

    fun write(settings: AppSettings) {
        runCatching {
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
                .putString(KEY_DAILY_TOKEN_BUDGET, settings.dailyTokenBudget.coerceAtLeast(0L).toString())
                .apply()
        }
    }

    /** Guarded prefs read — a corrupted value (e.g. AEADBadTagException)
     *  degrades to [default] instead of crashing the launch path. */
    private inline fun <T> pref(default: T, crossinline getter: () -> T): T =
        try {
            getter()
        } catch (_: Exception) {
            default
        }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_OLLAMA_KEY = "ollama_api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_GITHUB_PAT = "github_pat"
        private const val KEY_CONNECTIONS = "connections_json"
        private const val KEY_PROVIDER_ORDER = "provider_order_csv"
        private const val KEY_ACTIVE_PROVIDER = "active_provider_id"
        private const val KEY_DAILY_TOKEN_BUDGET = "daily_token_budget"
        private const val KEY_LEGACY_OLLAMA_ID = "legacy_ollama_connection_id"

        fun create(context: Context): EncryptedSettingsStore {
            return try {
                EncryptedSettingsStore(createEncryptedPrefs(context))
            } catch (_: Exception) {
                // First failure — the prefs file or its keyset is corrupted
                // (or the platform Keystore rejected the master key). Wipe the
                // file and retry once; secrets are lost but the app opens.
                runCatching {
                    File(context.filesDir, "$FILE_NAME.xml").delete()
                    EncryptedSettingsStore(createEncryptedPrefs(context))
                }.getOrElse {
                    // Even wipe+retry failed — the device Keystore itself is
                    // broken. Degrade to plain preferences (device-local, same
                    // sandbox) rather than crash-looping on every launch.
                    EncryptedSettingsStore(
                        context.getSharedPreferences("${FILE_NAME}_fallback", Context.MODE_PRIVATE),
                    )
                }
            }
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
