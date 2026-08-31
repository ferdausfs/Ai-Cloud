package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OpenAiChatRequestDto
import dev.repochat.core.data.remote.OpenAiCompatibleApi
import dev.repochat.core.data.remote.OpenAiMessageDto
import dev.repochat.core.data.remote.OpenAiResponseFormatDto
import dev.repochat.core.data.remote.mapHttpErrors
import dev.repochat.core.model.AppError
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.ServiceConnection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generic OpenAI-compatible /chat/completions client. One instance serves every
 * OPENAI_COMPATIBLE connection — base URL + key come from the connection row.
 */
@Singleton
class OpenAiCompatibleRepositoryImpl @Inject constructor(
    private val api: OpenAiCompatibleApi,
) {

    suspend fun chat(
        connection: ServiceConnection,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
    ): String {
        val base = connection.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            throw AppError.Configuration("OpenAI-compatible connection \"${connection.label}\" has no base URL.")
        }
        val model = connection.modelName.trim()
        if (model.isBlank()) {
            throw AppError.Configuration("OpenAI-compatible connection \"${connection.label}\" has no model name.")
        }
        val url = "$base/chat/completions"
        val headers = buildMap {
            val key = connection.apiKey.trim()
            if (key.isNotBlank()) put("Authorization", "Bearer $key")
        }
        val body = OpenAiChatRequestDto(
            model = model,
            messages = messages.map {
                OpenAiMessageDto(role = it.role.wireName, content = it.content)
            },
            responseFormat = if (jsonMode) OpenAiResponseFormatDto("json_object") else null,
            stream = false,
        )
        val response = mapHttpErrors(AppError.Provider.LLM) {
            api.chatCompletions(url, body, headers)
        }
        response.error?.message?.takeIf { it.isNotBlank() }?.let {
            throw AppError.Api(AppError.Provider.LLM, null, it)
        }
        val text = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (text.isBlank()) {
            throw AppError.Api(AppError.Provider.LLM, null, "Provider returned an empty response.")
        }
        return text
    }

    suspend fun test(connection: ServiceConnection): String {
        val reply = chat(
            connection = connection,
            messages = listOf(
                OllamaMessage(
                    role = dev.repochat.core.model.OllamaRole.USER,
                    content = "Reply with exactly: ok",
                ),
            ),
            jsonMode = false,
        )
        return reply.take(80)
    }

    /**
     * GET {base}/models. Returns sorted unique ids; empty on any failure so the
     * Settings form can fall back to a manual model field.
     */
    suspend fun listModels(connection: ServiceConnection): List<String> {
        val base = connection.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val url = "$base/models"
        val headers = buildMap {
            val key = connection.apiKey.trim()
            if (key.isNotBlank()) put("Authorization", "Bearer $key")
        }
        return try {
            val response = mapHttpErrors(AppError.Provider.LLM) {
                api.listModels(url, headers)
            }
            response.data
                .map { it.id.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
