package dev.repochat.core.domain

import dev.repochat.core.model.OllamaMessage

/**
 * Ollama Cloud API (https://ollama.com/api). Implementations must translate
 * HTTP/IO failures into [AppError] subtypes, including the 429 rate limit.
 */
interface OllamaService {

    /** Ping used by the Settings "Test connection" — returns the API version. */
    suspend fun version(): String

    /** Model names from GET /api/tags. Empty when unsupported. */
    suspend fun listModels(apiKeyOverride: String? = null): List<String>

    /**
     * One chat completion.
     * @param jsonMode when true, request structured JSON (`format=json`).
     * @param apiKeyOverride when non-null, use this key instead of Settings cache
     *   (per-connection Ollama keys).
     * @param model model id for this call.
     */
    suspend fun chat(
        model: String,
        messages: List<OllamaMessage>,
        jsonMode: Boolean = true,
        apiKeyOverride: String? = null,
    ): String
}
