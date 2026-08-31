package dev.repochat.core.data.repository

import dev.repochat.core.domain.OllamaService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.OllamaRole
import dev.repochat.core.model.ServiceConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRouterImplTest {

    private class MemSettings(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override fun cached(): AppSettings = state.value
        override suspend fun current(): AppSettings = state.value
        override suspend fun save(settings: AppSettings) {
            state.value = settings
        }
    }

    private class SeqOllama(
        private val results: ArrayDeque<Result<String>>,
    ) : OllamaService {
        override suspend fun version() = "t"
        override suspend fun chat(
            model: String,
            messages: List<OllamaMessage>,
            jsonMode: Boolean,
            apiKeyOverride: String?,
        ): String = results.removeFirst().getOrThrow()
    }

    @Test
    fun `falls back to next provider on rate limit`() = runBlocking {
        val ollamaConn = ServiceConnection(
            id = "o1",
            type = ConnectionType.OLLAMA,
            label = "Nemotron",
            apiKey = "k",
            modelName = "nemotron",
        )
        // Second connection also OLLAMA for simplicity (router uses same client).
        val groqAsOllama = ServiceConnection(
            id = "o2",
            type = ConnectionType.OLLAMA,
            label = "Groq",
            apiKey = "k2",
            modelName = "llama",
        )
        val settings = MemSettings(
            AppSettings(
                connections = listOf(ollamaConn, groqAsOllama),
                providerOrder = listOf("o1", "o2"),
            ),
        )
        val ollama = SeqOllama(
            ArrayDeque(
                listOf(
                    Result.failure(AppError.RateLimited(AppError.Provider.OLLAMA, "rate limited")),
                    Result.success("""{"action":"reply","message":"from second"}"""),
                ),
            ),
        )
        // OpenAI path unused when only OLLAMA connections — pass a stub that would throw if called.
        val openAi = OpenAiCompatibleRepositoryImpl(
            api = object : dev.repochat.core.data.remote.OpenAiCompatibleApi {
                override suspend fun chatCompletions(
                    url: String,
                    body: dev.repochat.core.data.remote.OpenAiChatRequestDto,
                    headers: Map<String, String>,
                ) = error("not used")
            },
        )
        val router = LlmRouterImpl(settings, ollama, openAi)
        val result = router.chat(
            messages = listOf(OllamaMessage(OllamaRole.USER, "hi")),
            jsonMode = true,
        )
        assertEquals("o2", result.connectionId)
        assertEquals("Groq", result.providerLabel)
        assertEquals("Nemotron", result.fellBackFrom)
        assertTrue(result.text.contains("from second"))
    }
}
