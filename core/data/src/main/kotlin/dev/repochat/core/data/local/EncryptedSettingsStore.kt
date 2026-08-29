package dev.repochat.core.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.repochat.core.model.AppSettings

/**
 * EncryptedSharedPreferences-backed store for secrets (API keys, PAT) and the
 * model name. Keys and values are both AES-256 encrypted with a Keystore-held
 * master key — nothing sensitive ever touches plain SharedPreferences.
 *
 * Note: androidx.security:crypto was deprecated upstream in 1.1.0, but the
 * product requirement here is explicitly EncryptedSharedPreferences, so it is
 * used intentionally.
 */
@Suppress("DEPRECATION")
class EncryptedSettingsStore private constructor(
    private val prefs: SharedPreferences,
) {

    fun read(): AppSettings = AppSettings(
        ollamaKey = prefs.getString(KEY_OLLAMA_KEY, "").orEmpty(),
        modelName = prefs.getString(KEY_MODEL_NAME, "").orEmpty(),
        githubPat = prefs.getString(KEY_GITHUB_PAT, "").orEmpty(),
    )

    fun write(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_OLLAMA_KEY, settings.ollamaKey.trim())
            .putString(KEY_MODEL_NAME, settings.modelName.trim())
            .putString(KEY_GITHUB_PAT, settings.githubPat.trim())
            .apply()
    }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_OLLAMA_KEY = "ollama_api_key"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_GITHUB_PAT = "github_pat"

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
