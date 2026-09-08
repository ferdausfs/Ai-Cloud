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

    /**
     * Streaming variant of [chat]. [onDelta] receives the cumulative reply
     * text as it arrives (best effort — providers without streaming support
     * emit exactly one delta with the full text before returning). The final
     * complete text is also returned in the [LlmChatResult].
     */
    suspend fun chatStreaming(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String? = null,
        onDelta: (String) -> Unit,
    ): LlmChatResult = chat(messages, jsonMode, preferredConnectionId).also {
        onDelta(it.text)
    }

    /** Trivial ping against one connection (Settings "Test"). */
    suspend fun test(connection: ServiceConnection): String

    /**
     * Live model ids for one connection. Throws typed [dev.repochat.core.model.AppError]s
     * (Unauthorized / RateLimited / Network / Configuration) so callers can
     * explain WHY the list is unavailable; an empty return means the provider
     * genuinely listed no models.
     */
    suspend fun listModels(connection: ServiceConnection): List<String>
}
