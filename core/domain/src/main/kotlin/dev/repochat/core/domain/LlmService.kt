package dev.repochat.core.domain

import dev.repochat.core.model.LlmChatResult
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.ServiceConnection

/**
 * Single entry point for chat completions. Implementations pick the right
 * backend (Ollama NDJSON vs OpenAI-compatible) and may auto-fallback across
 * [dev.repochat.core.model.AppSettings.providerOrder] on rate limits.
 */
interface LlmService {

    /**
     * @param jsonMode when true, ask the backend for JSON-object output
     *   (Ollama `format=json`, OpenAI `response_format=json_object`).
     * @param preferredConnectionId force one connection (manual picker).
     */
    suspend fun chat(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String? = null,
    ): LlmChatResult

    /** Trivial ping against one connection (Settings "Test"). */
    suspend fun test(connection: ServiceConnection): String

    /**
     * Live model ids for [connection] (OpenAI `GET /models` or Ollama `GET /api/tags`).
     * Empty list when the endpoint is unavailable — UI should fall back to manual entry.
     */
    suspend fun listModels(connection: ServiceConnection): List<String>
}
