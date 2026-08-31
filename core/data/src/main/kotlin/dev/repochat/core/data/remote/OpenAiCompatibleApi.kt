package dev.repochat.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible chat completions (Groq, Cerebras, OpenRouter, Together, …).
 * Full URL is supplied per call so each connection can use its own base.
 */
interface OpenAiCompatibleApi {

    @retrofit2.http.POST
    suspend fun chatCompletions(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Body body: OpenAiChatRequestDto,
        @retrofit2.http.Header("Authorization") authorization: String,
    ): OpenAiChatResponseDto
}

@Serializable
data class OpenAiChatRequestDto(
    val model: String,
    val messages: List<OpenAiMessageDto>,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormatDto? = null,
    val stream: Boolean = false,
    /** Soft cap so free-tier providers don't hang forever. */
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class OpenAiMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class OpenAiResponseFormatDto(val type: String)

@Serializable
data class OpenAiChatResponseDto(
    val choices: List<OpenAiChoiceDto> = emptyList(),
    val error: OpenAiErrorBodyDto? = null,
)

@Serializable
data class OpenAiChoiceDto(
    val message: OpenAiMessageDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiErrorBodyDto(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)

@Serializable
data class OpenAiErrorDto(
    val error: OpenAiErrorBodyDto? = null,
    val message: String? = null,
)
