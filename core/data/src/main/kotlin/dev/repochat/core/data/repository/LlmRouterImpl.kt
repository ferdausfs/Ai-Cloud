package dev.repochat.core.data.repository

import dev.repochat.core.domain.LlmService
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.LlmChatResult
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.ServiceConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries LLM connections in [AppSettings.providerOrder] order. On rate-limit
 * (HTTP 429 / [AppError.RateLimited]) advances to the next provider.
 */
@Singleton
class LlmRouterImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val ollama: OllamaService,
    private val openAi: OpenAiCompatibleRepositoryImpl,
) : LlmService {

    override suspend fun chat(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String?,
    ): LlmChatResult {
        val snap = settings.current()
        val ordered = snap.llmConnectionsOrdered()
        if (ordered.isEmpty()) {
            // Legacy single-key path: synthesize an Ollama connection from flat fields.
            val legacyKey = snap.ollamaKey.trim()
            val legacyModel = snap.modelName.trim()
            if (legacyKey.isBlank() || legacyModel.isBlank()) {
                throw AppError.Configuration(
                    "No AI provider configured. Add Ollama or an OpenAI-compatible connection in Settings.",
                )
            }
            val text = ollama.chat(legacyModel, messages, jsonMode = jsonMode, apiKeyOverride = legacyKey)
            return LlmChatResult(text = text, connectionId = "legacy-ollama", providerLabel = "Ollama")
        }

        val preferred = preferredConnectionId?.let { id -> ordered.firstOrNull { it.id == id } }
            ?: snap.activeProviderId?.let { id -> ordered.firstOrNull { it.id == id } }

        val queue = buildList {
            preferred?.let { add(it) }
            ordered.filter { it.id != preferred?.id }.forEach { add(it) }
        }

        var lastError: AppError? = null
        var fellBackFrom: String? = null
        var firstLabel: String? = null

        for ((index, conn) in queue.withIndex()) {
            if (index == 0) firstLabel = conn.label
            try {
                val text = invokeOne(conn, messages, jsonMode)
                val noteFrom = if (index > 0) firstLabel else null
                return LlmChatResult(
                    text = text,
                    connectionId = conn.id,
                    providerLabel = conn.label.ifBlank { conn.type.name },
                    fellBackFrom = noteFrom ?: fellBackFrom,
                )
            } catch (e: AppError.RateLimited) {
                lastError = e
                if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                continue
            } catch (e: AppError) {
                // Non-rate-limit on preferred/first: still try next only for rate limits.
                // For other errors on non-last, keep going only if rate-limit-like message.
                if (isRateLimitLike(e) && index < queue.lastIndex) {
                    lastError = e
                    if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                    continue
                }
                throw e
            }
        }
        throw lastError ?: AppError.Configuration("Every configured AI provider failed.")
    }

    override suspend fun test(connection: ServiceConnection): String =
        when (connection.type) {
            ConnectionType.OLLAMA -> {
                val model = connection.modelName.trim().ifBlank {
                    settings.current().modelName.trim()
                }
                if (model.isBlank()) {
                    // version ping doesn't need a model
                    ollama.version()
                } else {
                    ollama.chat(
                        model = model,
                        messages = listOf(
                            OllamaMessage(
                                role = dev.repochat.core.model.OllamaRole.USER,
                                content = "Reply with exactly: ok",
                            ),
                        ),
                        jsonMode = false,
                        apiKeyOverride = connection.apiKey.trim().ifBlank { null },
                    ).take(80)
                }
            }
            ConnectionType.OPENAI_COMPATIBLE -> openAi.test(connection)
            ConnectionType.GITHUB -> throw AppError.Configuration("Not an LLM connection.")
        }

    private suspend fun invokeOne(
        conn: ServiceConnection,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
    ): String = when (conn.type) {
        ConnectionType.OLLAMA -> {
            val model = conn.modelName.trim().ifBlank {
                settings.current().modelName.trim()
            }
            if (model.isBlank()) {
                throw AppError.Configuration("Ollama connection \"${conn.label}\" has no model name.")
            }
            ollama.chat(
                model = model,
                messages = messages,
                jsonMode = jsonMode,
                apiKeyOverride = conn.apiKey.trim().ifBlank { null },
            )
        }
        ConnectionType.OPENAI_COMPATIBLE -> openAi.chat(conn, messages, jsonMode)
        ConnectionType.GITHUB -> throw AppError.Configuration("Not an LLM connection.")
    }

    private fun isRateLimitLike(e: AppError): Boolean {
        if (e is AppError.RateLimited) return true
        val m = e.userMessage.lowercase()
        return "rate limit" in m || "too many requests" in m || "quota" in m ||
            "daily limit" in m || "tokens per day" in m
    }
}
