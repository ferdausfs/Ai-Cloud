package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OpenAiChatRequestDto
import dev.repochat.core.data.remote.OpenAiChatResponseDto
import dev.repochat.core.data.remote.OpenAiChoiceDto
import dev.repochat.core.data.remote.OpenAiCompatibleApi
import dev.repochat.core.data.remote.OpenAiImageRequestDto
import dev.repochat.core.data.remote.OpenAiImagesResponseDto
import dev.repochat.core.data.remote.OpenAiMessageDto
import dev.repochat.core.data.remote.OpenAiModelsDto
import dev.repochat.core.data.remote.OpenAiSpeechRequestDto
import dev.repochat.core.data.remote.OpenAiUsageDto
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.OllamaRole
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.UsageKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Router-level metering tests: usage captured from provider-reported counts,
 * estimated fallback, and the daily token budget hard stop.
 */
class UsageMeteringTest {

    private class MemSettings(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override fun cached(): AppSettings = state.value
        override suspend fun current(): AppSettings = state.value
        override suspend fun save(settings: AppSettings) {
            state.value = settings
        }
    }

    private object NoOllama : OllamaService {
        override suspend fun version() = "t"
        override suspend fun listModels(apiKeyOverride: String?) = emptyList<String>()
        override suspend fun chat(
            model: String,
            messages: List<OllamaMessage>,
            jsonMode: Boolean,
            apiKeyOverride: String?,
        ): String = error("not used")
    }

    /** OpenAI-compatible fake: reply "ok", optionally with reported usage. */
    private class UsageApi(
        private val reportedUsage: OpenAiUsageDto?,
        private val sseWithUsage: Boolean,
    ) : OpenAiCompatibleApi {
        override suspend fun chatCompletions(
            url: String,
            body: OpenAiChatRequestDto,
            headers: Map<String, String>,
        ): OpenAiChatResponseDto = OpenAiChatResponseDto(
            choices = listOf(
                OpenAiChoiceDto(
                    message = OpenAiMessageDto(
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("ok"),
                    ),
                ),
            ),
            usage = reportedUsage,
        )

        override suspend fun chatCompletionsStream(
            url: String,
            body: OpenAiChatRequestDto,
            headers: Map<String, String>,
        ): ResponseBody {
            val usageChunk = if (sseWithUsage && reportedUsage != null) {
                """data: {"usage":{"prompt_tokens":${reportedUsage.promptTokens},""" +
                    """"completion_tokens":${reportedUsage.completionTokens}}}"""
            } else {
                ""
            }
            val sse = listOf(
                """data: {"choices":[{"delta":{"content":"ok"}}]}""",
                usageChunk,
                "data: [DONE]",
            ).filter { it.isNotBlank() }.joinToString("\n")
            return ResponseBody.create(null, sse)
        }

        override suspend fun listModels(url: String, headers: Map<String, String>): OpenAiModelsDto =
            error("not used")

        override suspend fun generateImage(
            url: String,
            body: OpenAiImageRequestDto,
            headers: Map<String, String>,
        ): OpenAiImagesResponseDto = error("not used")

        override suspend fun download(url: String): ResponseBody = error("not used")

        override suspend fun speech(
            url: String,
            body: OpenAiSpeechRequestDto,
            headers: Map<String, String>,
        ): ResponseBody = error("not used")
    }

    private fun settingsWith(extra: (AppSettings) -> AppSettings = { it }): AppSettings {
        val conn = ServiceConnection(
            id = "c1",
            type = ConnectionType.OPENAI_COMPATIBLE,
            label = "Groq",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "k",
            modelName = "llama",
        )
        val s = AppSettings(
            connections = listOf(conn),
            providerOrder = listOf("c1"),
        )
        return extra(s)
    }

    private fun router(settings: AppSettings, usage: FakeUsageRepository): LlmRouterImpl =
        LlmRouterImpl(
            MemSettings(settings),
            NoOllama,
            OpenAiCompatibleRepositoryImpl(api = UsageApi(OpenAiUsageDto(100, 40), true)),
            usage,
        )

    @Test
    fun `streaming chat records provider-reported usage`() = runBlocking {
        val usage = FakeUsageRepository()
        val r = router(settingsWith(), usage)
        val result = r.chatStreaming(listOf(OllamaMessage(OllamaRole.USER, "hi")), false) { }
        assertNotNull(result.usage)
        assertEquals(100L, result.usage?.inputTokens)
        assertEquals(40L, result.usage?.outputTokens)
        assertTrue(result.usage?.reported == true)
        assertEquals(1, usage.events.size)
        assertEquals(UsageKind.CHAT, usage.events.first().kind)
        assertEquals("Groq", usage.events.first().provider)
        assertEquals(100L, usage.events.first().inputTokens)
    }

    @Test
    fun `non-streaming chat records usage`() = runBlocking {
        val usage = FakeUsageRepository()
        val r = router(settingsWith(), usage)
        r.chat(listOf(OllamaMessage(OllamaRole.USER, "hi")), false)
        assertEquals(1, usage.events.size)
        assertEquals(140L, usage.events.first().totalTokens)
    }

    @Test
    fun `chat falls back to estimate when provider reports nothing`() = runBlocking {
        val usage = FakeUsageRepository()
        val settings = settingsWith()
        val r = LlmRouterImpl(
            MemSettings(settings),
            NoOllama,
            OpenAiCompatibleRepositoryImpl(api = UsageApi(reportedUsage = null, sseWithUsage = false)),
            usage,
        )
        val result = r.chatStreaming(
            messages = listOf(OllamaMessage(OllamaRole.USER, "abcd".repeat(20))),
            jsonMode = false,
        ) { }
        assertNotNull(result.usage)
        assertTrue(!result.usage!!.reported)
        assertEquals(20L, result.usage?.inputTokens)
        assertEquals(1L, result.usage?.outputTokens) // "ok" → 1 token
        assertEquals(1, usage.events.size)
        assertTrue(!usage.events.first().reported)
    }

    @Test
    fun `budget not reached allows chat`() = runBlocking {
        val usage = FakeUsageRepository()
        val r = router(settingsWith { it.copy(dailyTokenBudget = 10_000L) }, usage)
        val result = r.chat(listOf(OllamaMessage(OllamaRole.USER, "hi")), false)
        assertEquals("ok", result.text)
        assertEquals(1, usage.events.size)
    }

    @Test
    fun `budget reached blocks chat with clear error`() = runBlocking {
        val usage = FakeUsageRepository(fixedTotals = dev.repochat.core.model.UsageTotals(3, 900, 600))
        val r = router(settingsWith { it.copy(dailyTokenBudget = 1_500L) }, usage)
        val error = try {
            r.chat(listOf(OllamaMessage(OllamaRole.USER, "hi")), false)
            null
        } catch (e: AppError.Configuration) {
            e
        }
        assertNotNull("Expected Configuration error", error)
        assertTrue(error!!.userMessage.contains("budget"))
        assertTrue(error.userMessage.contains("1500"))
        // Blocked call must not meter anything new.
        assertEquals(0, usage.events.size)
    }

    @Test
    fun `zero budget means unlimited`() = runBlocking {
        val usage = FakeUsageRepository(fixedTotals = dev.repochat.core.model.UsageTotals(99, 9_000, 9_000))
        val r = router(settingsWith { it.copy(dailyTokenBudget = 0L) }, usage)
        r.chat(listOf(OllamaMessage(OllamaRole.USER, "hi")), false)
        assertEquals(1, usage.events.size)
    }
}
