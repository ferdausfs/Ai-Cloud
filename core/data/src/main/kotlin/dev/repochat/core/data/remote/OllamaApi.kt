package dev.repochat.core.data.remote

import kotlinx.serialization.Serializable

interface OllamaApi {

    /** GET /api/version — used as the Settings "test connection" ping. */
    @retrofit2.http.GET("api/version")
    suspend fun version(): OllamaVersionDto

    /** POST /api/chat — non-streaming completion with structured (JSON) output. */
    @retrofit2.http.POST("api/chat")
    suspend fun chat(@retrofit2.http.Body body: OllamaChatRequestDto): OllamaChatResponseDto
}

@Serializable
data class OllamaVersionDto(val version: String? = null)

@Serializable
data class OllamaChatRequestDto(
    val model: String,
    val messages: List<OllamaMessageDto>,
    val stream: Boolean = false,
    val format: String = "json",
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
