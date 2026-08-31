package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OpenAiChatRequestDto
import dev.repochat.core.data.remote.OpenAiChatResponseDto
import dev.repochat.core.data.remote.OpenAiCompatibleApi
import dev.repochat.core.data.remote.OpenAiModelDto
import dev.repochat.core.data.remote.OpenAiModelsDto
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiListModelsTest {

    @Test
    fun `listModels returns sorted ids`() = runBlocking {
        val api = object : OpenAiCompatibleApi {
            override suspend fun chatCompletions(
                url: String,
                body: OpenAiChatRequestDto,
                headers: Map<String, String>,
            ) = OpenAiChatResponseDto()

            override suspend fun listModels(
                url: String,
                headers: Map<String, String>,
            ): OpenAiModelsDto {
                assertTrue(url.endsWith("/models"))
                return OpenAiModelsDto(
                    data = listOf(
                        OpenAiModelDto("llama-3.3-70b-versatile"),
                        OpenAiModelDto("gemma2-9b-it"),
                    ),
                )
            }
        }
        val repo = OpenAiCompatibleRepositoryImpl(api)
        val ids = repo.listModels(
            ServiceConnection(
                id = "1",
                type = ConnectionType.OPENAI_COMPATIBLE,
                label = "Groq",
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = "sk-test",
            ),
        )
        assertEquals(listOf("gemma2-9b-it", "llama-3.3-70b-versatile"), ids)
    }

    @Test
    fun `listModels returns empty on failure`() = runBlocking {
        val api = object : OpenAiCompatibleApi {
            override suspend fun chatCompletions(
                url: String,
                body: OpenAiChatRequestDto,
                headers: Map<String, String>,
            ) = OpenAiChatResponseDto()

            override suspend fun listModels(
                url: String,
                headers: Map<String, String>,
            ): OpenAiModelsDto = error("network")
        }
        val repo = OpenAiCompatibleRepositoryImpl(api)
        assertTrue(
            repo.listModels(
                ServiceConnection(
                    id = "1",
                    type = ConnectionType.OPENAI_COMPATIBLE,
                    label = "X",
                    baseUrl = "https://example.com/v1",
                    apiKey = "k",
                ),
            ).isEmpty(),
        )
    }
}
