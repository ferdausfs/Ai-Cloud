package dev.repochat.core.data.remote

import kotlinx.serialization.Serializable

interface OllamaApi {

    /** GET /api/version — used as the Settings "test connection" ping. */
    @retrofit2.http.GET("api/version")
    suspend fun version(): OllamaVersionDto

    /**
     * GET /api/tags — local (and some cloud) model listing.
     * Cloud may return empty or fail; callers fall back to a curated list.
     */
    @retrofit2.http.GET("api/tags")
    suspend fun tags(): OllamaTagsDto

    /**
     * POST /api/chat — returns the raw body.
     *
     * Ollama Cloud often replies with NDJSON (one JSON object per line / chunk)
     * even when the request sets `"stream": false`. Decoding the whole body as a
     * single [OllamaChatResponseDto] fails with "Expected EOF"; the repository
     * parses each line and concatenates `message.content` deltas instead.
     */
    @retrofit2.http.POST("api/chat")
    suspend fun chat(@retrofit2.http.Body body: OllamaChatRequestDto): okhttp3.ResponseBody
}

@Serializable
data class OllamaVersionDto(val version: String? = null)

@Serializable
data class OllamaTagsDto(
    val models: List<OllamaTagModelDto> = emptyList(),
)

@Serializable
data class OllamaTagModelDto(
    val name: String = "",
    val model: String? = null,
)

@Serializable
data class OllamaChatRequestDto(
    val model: String,
    val messages: List<OllamaMessageDto>,
    val stream: Boolean = false,
    /** "json" for tool schema turns; null for free-form general chat. */
    val format: String? = "json",
)

@Serializable
data class OllamaMessageDto(
    val role: String,
    val content: String,
    /** Base64 image payloads for vision models (Ollama /api/chat). */
    val images: List<String>? = null,
)

@Serializable
data class OllamaChatResponseDto(
    val message: OllamaMessageDto? = null,
    val response: String? = null,
    val error: String? = null,
)

/** Ollama error bodies use `{"error": "..."}` (not GitHub's `{"message": "..."}`). */
@Serializable
data class OllamaErrorDto(val error: String? = null)
