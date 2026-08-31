package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OllamaApi
import dev.repochat.core.data.remote.OllamaChatRequestDto
import dev.repochat.core.data.remote.OllamaChatResponseDto
import dev.repochat.core.data.remote.OllamaKeyOverride
import dev.repochat.core.data.remote.OllamaMessageDto
import dev.repochat.core.data.remote.mapHttpErrors
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.model.AppError
import dev.repochat.core.model.OllamaMessage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

@Singleton
class OllamaRepositoryImpl @Inject constructor(
    private val api: OllamaApi,
    private val json: Json,
) : OllamaService {

    override suspend fun version(): String =
        mapHttpErrors(AppError.Provider.OLLAMA) { api.version() }.version
            ?: "unknown"

    override suspend fun chat(
        model: String,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        apiKeyOverride: String?,
    ): String {
        val rawBody = OllamaKeyOverride.withKeySuspend(apiKeyOverride) {
            mapHttpErrors(AppError.Provider.OLLAMA) {
                api.chat(
                    OllamaChatRequestDto(
                        model = model,
                        messages = messages.map {
                            OllamaMessageDto(
                                role = it.role.wireName,
                                content = it.content,
                                images = it.images,
                            )
                        },
                        format = if (jsonMode) "json" else null,
                    ),
                )
            }.string()
        }

        // Ollama may return either a single JSON object or NDJSON chunks
        // (one object per line). Concatenate every message.content delta in
        // order — taking only the last line would drop the action payload.
        val contentBuilder = StringBuilder()
        var lastError: String? = null
        var lastResponseField: String? = null

        rawBody.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val chunk = try {
                    json.decodeFromString(OllamaChatResponseDto.serializer(), line)
                } catch (_: Exception) {
                    return@forEach
                }
                chunk.error?.takeIf { it.isNotBlank() }?.let { lastError = it }
                chunk.message?.content?.let { contentBuilder.append(it) }
                chunk.response?.takeIf { it.isNotBlank() }?.let { lastResponseField = it }
            }

        lastError?.let {
            throw AppError.Api(AppError.Provider.OLLAMA, null, it)
        }

        val content = contentBuilder.toString()
        if (content.isNotBlank()) return content

        lastResponseField?.let { return it }

        throw AppError.Api(AppError.Provider.OLLAMA, null, "Ollama returned an empty response.")
    }
}
