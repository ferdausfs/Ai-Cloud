package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OpenAiChatRequestDto
import dev.repochat.core.data.remote.OpenAiCompatibleApi
import dev.repochat.core.data.remote.OpenAiMessageDto
import dev.repochat.core.data.remote.OpenAiResponseFormatDto
import dev.repochat.core.data.remote.mapHttpErrors
import dev.repochat.core.model.AppError
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.matchOpenAiPreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generic OpenAI-compatible /chat/completions client. One instance serves every
 * OPENAI_COMPATIBLE connection — base URL + key come from the connection row.
 *
 * Provider-specific behavior is driven by the matched [dev.repochat.core.model.ProviderPreset]:
 * extra static headers (OpenRouter attribution) and whether `response_format`
 * is safe to send (Experiential Labs rejects extra sampling parameters).
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
        val preset = matchOpenAiPreset(base)
        val url = "$base/chat/completions"
        val body = OpenAiChatRequestDto(
            model = model,
            messages = messages.map {
                OpenAiMessageDto(role = it.role.wireName, content = messageContent(it))
            },
            responseFormat = if (jsonMode && preset.supportsJsonResponseFormat) {
                OpenAiResponseFormatDto("json_object")
            } else {
                null
            },
            stream = false,
        )
        val response = mapHttpErrors(AppError.Provider.LLM) {
            api.chatCompletions(url, body, headersFor(connection, preset.extraHeaders))
        }
        response.error?.message?.takeIf { it.isNotBlank() }?.let {
            throw AppError.Api(AppError.Provider.LLM, null, it)
        }
        val text = responseText(response.choices.firstOrNull()?.message?.content)
        if (text.isBlank()) {
            throw AppError.Api(AppError.Provider.LLM, null, "Provider returned an empty response.")
        }
        return text
    }

    /**
     * Real-API connection test that does NOT spend tokens: authenticated
     * `GET /models`. 200 proves the endpoint + key; 401/403/429/5xx map to
     * precise, human-readable [AppError]s for the Settings UI.
     */
    suspend fun test(connection: ServiceConnection): String {
        val models = listModels(connection, strict = true)
        return if (models.isEmpty()) {
            "endpoint reachable — no models listed"
        } else {
            "endpoint reachable — ${models.size} model${if (models.size == 1) "" else "s"} available"
        }
    }

    /**
     * Live model ids. Throws typed [AppError]s (Unauthorized / RateLimited /
     * Network / Api) so callers can distinguish a bad key from offline; the
     * legacy non-throwing behavior is available via [listModelsOrEmpty].
     */
    suspend fun listModels(
        connection: ServiceConnection,
        strict: Boolean = false,
    ): List<String> {
        val base = connection.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) {
            if (strict) {
                throw AppError.Configuration("Connection \"${connection.label}\" has no base URL.")
            }
            return emptyList()
        }
        val url = "$base/models"
        val response = mapHttpErrors(AppError.Provider.LLM) {
            api.listModels(url, headersFor(connection, matchOpenAiPreset(base).extraHeaders))
        }
        return response.data
            .map { it.id.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    /** Non-throwing variant kept for backward-tolerant callers. */
    suspend fun listModelsOrEmpty(connection: ServiceConnection): List<String> = try {
        listModels(connection)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    /* ---------------------------- helpers ---------------------------- */

    private fun headersFor(
        connection: ServiceConnection,
        extra: Map<String, String>,
    ): Map<String, String> = buildMap {
        val key = connection.apiKey.trim()
        if (key.isNotBlank()) put("Authorization", "Bearer $key")
        extra.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) put(name, value)
        }
    }

    /**
     * Builds the OpenAI `content` field: a plain string for text-only turns,
     * or an array of typed parts when images are attached — the standard
     * vision shape (`image_url` with a base64 data URI) understood by
     * OpenRouter, Groq vision models, GPT-family and most compatible stacks.
     */
    internal fun messageContent(message: OllamaMessage) = when {
        message.images.isNullOrEmpty() -> JsonPrimitive(message.content)
        else -> buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "text")
                    put("text", message.content)
                },
            )
            message.images.orEmpty().forEachIndexed { index, base64 ->
                if (base64.isBlank()) return@forEachIndexed
                val mime = message.imageMimeTypes?.getOrNull(index)
                    ?.takeIf { it.startsWith("image/") }
                    ?: "image/jpeg"
                add(
                    buildJsonObject {
                        put("type", "image_url")
                        put(
                            "image_url",
                            buildJsonObject {
                                put("url", "data:$mime;base64,$base64")
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * Extracts assistant text from the response `content`, which may be a
     * plain string or an array of typed parts on some providers.
     */
    internal fun responseText(content: JsonElement?): String = when (content) {
        null -> ""
        is JsonPrimitive -> content.content.trim()
        is JsonArray -> buildString {
            content.forEach { part ->
                if (part is JsonObject &&
                    (part["type"] as? JsonPrimitive)?.content == "text"
                ) {
                    append((part["text"] as? JsonPrimitive)?.content.orEmpty())
                }
            }
        }.trim()
        else -> ""
    }
}
