package dev.repochat.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI-compatible chat completions (Groq, Cerebras, OpenRouter, Together,
 * Fireworks, Experiential Labs, …). Full URL is supplied per call so each
 * connection can use its own base.
 */
interface OpenAiCompatibleApi {

    @retrofit2.http.POST
    suspend fun chatCompletions(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Body body: OpenAiChatRequestDto,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): OpenAiChatResponseDto

    /**
     * Server-sent-events variant ([stream] = true in the body). The raw
     * `text/event-stream` body is parsed by the repository — errors on this
     * endpoint surface as HTTP codes, not JSON envelopes.
     */
    @retrofit2.http.Streaming
    @retrofit2.http.POST
    suspend fun chatCompletionsStream(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Body body: OpenAiChatRequestDto,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): okhttp3.ResponseBody

    @retrofit2.http.GET
    suspend fun listModels(
        @retrofit2.http.Url url: String,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): OpenAiModelsDto
}

@Serializable
data class OpenAiChatRequestDto(
    val model: String,
    val messages: List<OpenAiMessageDto>,
    @SerialName("response_format") val responseFormat: OpenAiResponseFormatDto? = null,
    val stream: Boolean = false,
    /**
     * Intentionally null/omitted: `write_file` answers carry whole files, so a
     * token cap could silently truncate a commit. Free-tier hang protection is
     * handled by OkHttp read timeouts instead.
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

/**
 * `content` is either a JSON string (text-only turn) or an array of typed
 * parts (text + `image_url` data URIs for vision models). Modeled as
 * [JsonElement] so both shapes serialize exactly as the wire format expects.
 */
@Serializable
data class OpenAiMessageDto(
    val role: String,
    val content: JsonElement,
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

@Serializable
data class OpenAiModelsDto(
    val data: List<OpenAiModelDto> = emptyList(),
)

@Serializable
data class OpenAiModelDto(
    val id: String = "",
)

