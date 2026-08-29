package dev.repochat.core.domain

import dev.repochat.core.model.OllamaMessage

/**
 * Ollama Cloud API (https://ollama.com/api). Implementations must translate
 * HTTP/IO failures into [AppError] subtypes, including the 429 rate limit.
 */
interface OllamaService {

    /** Ping used by the Settings "Test connection" — returns the API version. */
    suspend fun version(): String

    /** One chat completion with structured (JSON) output. */
    suspend fun chat(model: String, messages: List<OllamaMessage>): String
}
