package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OllamaApi
import dev.repochat.core.data.remote.OllamaChatRequestDto
import dev.repochat.core.data.remote.OllamaMessageDto
import dev.repochat.core.data.remote.mapHttpErrors
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.model.AppError
import dev.repochat.core.model.OllamaMessage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OllamaRepositoryImpl @Inject constructor(
    private val api: OllamaApi,
) : OllamaService {

    override suspend fun version(): String =
        mapHttpErrors(AppError.Provider.OLLAMA) { api.version() }.version
            ?: "unknown"

    override suspend fun chat(model: String, messages: List<OllamaMessage>): String {
        val response = mapHttpErrors(AppError.Provider.OLLAMA) {
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
                )
            )
        }
        if (!response.error.isNullOrBlank()) {
            throw AppError.Api(AppError.Provider.OLLAMA, null, response.error.orEmpty())
        }
        return response.message?.content
            ?: response.response
            ?: throw AppError.Api(AppError.Provider.OLLAMA, null, "Ollama returned an empty response.")
    }
}
