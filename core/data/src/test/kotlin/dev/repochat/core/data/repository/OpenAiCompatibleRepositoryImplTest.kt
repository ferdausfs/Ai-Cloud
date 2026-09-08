package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OpenAiChatRequestDto
import dev.repochat.core.data.remote.OpenAiChatResponseDto
import dev.repochat.core.data.remote.OpenAiCompatibleApi
import dev.repochat.core.data.remote.OpenAiErrorBodyDto
import dev.repochat.core.data.remote.OpenAiMessageDto
import dev.repochat.core.data.remote.OpenAiModelDto
import dev.repochat.core.data.remote.OpenAiModelsDto
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.OllamaRole
import dev.repochat.core.model.ServiceConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fakes the Retrofit [OpenAiCompatibleApi] and records every request so the
 * repository's request-shaping (headers, response_format, image parts) can be
 * asserted exactly.
 */
private class RecordingApi : OpenAiCompatibleApi {
    var lastChatUrl: String? = null
    var lastChatBody: OpenAiChatRequestDto? = null
    var lastChatHeaders: Map<String, String> = emptyMap()
    var lastModelsUrl: String? = null
    var lastModelsHeaders: Map<String, String> = emptyMap()
    var modelsResponse: OpenAiModelsDto = OpenAiModelsDto()
    var chatError: Throwable? = null

    override suspend fun chatCompletions(
        url: String,
        body: OpenAiChatRequestDto,
        headers: Map<String, String>,
    ): OpenAiChatResponseDto {
        lastChatUrl = url
        lastChatBody = body
        lastChatHeaders = headers
        chatError?.let { throw it }
        return OpenAiChatResponseDto(
            choices = listOf(
                dev.repochat.core.data.remote.OpenAiChoiceDto(
                    message = OpenAiMessageDto(
                        role = "assistant",
                        content = kotlinx.serialization.json.JsonPrimitive("ok"),
                    ),
                ),
            ),
        )
    }

    override suspend fun chatCompletionsStream(
        url: String,
        body: OpenAiChatRequestDto,
        headers: Map<String, String>,
    ): okhttp3.ResponseBody {
        lastChatUrl = url
        lastChatBody = body
        lastChatHeaders = headers
        chatError?.let { throw it }
        // Minimal SSE body: two deltas + [DONE].
        val sse = """
            data: {"choices":[{"delta":{"content":"ok"}}]}
            data: {"choices":[{"delta":{"content":"!"}}]}
            data: [DONE]
        """.trimIndent()
        return okhttp3.ResponseBody.create(null, sse)
    }

    override suspend fun listModels(
        url: String,
        headers: Map<String, String>,
    ): OpenAiModelsDto {
        lastModelsUrl = url
        lastModelsHeaders = headers
        return modelsResponse
    }
}

class OpenAiCompatibleRepositoryImplTest {

    private fun connection(
        baseUrl: String,
        label: String = "X",
        apiKey: String = "k-test-1234",
    ) = ServiceConnection(
        id = "1",
        type = ConnectionType.OPENAI_COMPATIBLE,
        label = label,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelName = "model-a",
    )

    @Test
    fun `listModels returns sorted distinct ids`() = runBlocking {
        val api = RecordingApi().apply {
            modelsResponse = OpenAiModelsDto(
                data = listOf(
                    OpenAiModelDto("llama-3.3-70b-versatile"),
                    OpenAiModelDto("gemma2-9b-it"),
                    OpenAiModelDto("llama-3.3-70b-versatile"),
                ),
            )
        }
        val repo = OpenAiCompatibleRepositoryImpl(api)
        val ids = repo.listModels(connection("https://api.groq.com/openai/v1", "Groq"))
        assertEquals(listOf("gemma2-9b-it", "llama-3.3-70b-versatile"), ids)
        assertEquals("https://api.groq.com/openai/v1/models", api.lastModelsUrl)
        assertEquals("Bearer k-test-1234", api.lastModelsHeaders["Authorization"])
    }

    @Test
    fun `listModels propagates typed errors`() = runBlocking {
        val api = RecordingApi().apply {
            chatError = null
            modelsResponse = OpenAiModelsDto()
        }
        // Simulate transport failure by making the fake throw.
        val throwing = object : OpenAiCompatibleApi by api {
            override suspend fun listModels(
                url: String,
                headers: Map<String, String>,
            ): OpenAiModelsDto = throw retrofit2.HttpException(
                retrofit2.Response.error<OpenAiModelsDto>(
                    401,
                    okhttp3.ResponseBody.create(null, """{"error":{"message":"bad key"}}"""),
                ),
            )
        }
        val repo = OpenAiCompatibleRepositoryImpl(throwing)
        try {
            repo.listModels(connection("https://example.com/v1"))
            org.junit.Assert.fail("expected Unauthorized")
        } catch (e: AppError.Unauthorized) {
            assertTrue(e.userMessage.contains("key", ignoreCase = true))
        }
    }

    @Test
    fun `listModelsOrEmpty swallows failures`() = runBlocking {
        val throwing = object : OpenAiCompatibleApi {
            override suspend fun chatCompletions(
                url: String,
                body: OpenAiChatRequestDto,
                headers: Map<String, String>,
            ) = error("not used")
            override suspend fun chatCompletionsStream(
                url: String,
                body: OpenAiChatRequestDto,
                headers: Map<String, String>,
            ): okhttp3.ResponseBody = error("not used")
            override suspend fun listModels(
                url: String,
                headers: Map<String, String>,
            ): OpenAiModelsDto = error("network down")
        }
        val repo = OpenAiCompatibleRepositoryImpl(throwing)
        assertTrue(
            repo.listModelsOrEmpty(connection("https://example.com/v1")).isEmpty(),
        )
    }

    @Test
    fun `test uses models endpoint and reports availability`() = runBlocking {
        val api = RecordingApi().apply {
            modelsResponse = OpenAiModelsDto(
                data = listOf(OpenAiModelDto("a"), OpenAiModelDto("b"), OpenAiModelDto("c")),
            )
        }
        val repo = OpenAiCompatibleRepositoryImpl(api)
        val detail = repo.test(connection("https://api.experientiallabs.ai/v1", "Experiential Labs"))
        // GET /models — a real request that validates key + endpoint with no token cost.
        assertNotNull(api.lastModelsUrl)
        assertTrue(api.lastChatBody == null)
        assertTrue(detail.contains("3 models"))
    }

    @Test
    fun `experiential requests omit response_format and target official endpoint`() = runBlocking {
        val api = RecordingApi()
        val repo = OpenAiCompatibleRepositoryImpl(api)
        repo.chat(
            connection = connection("https://api.experientiallabs.ai/v1", "Experiential Labs"),
            messages = listOf(OllamaMessage(OllamaRole.USER, "hi")),
            jsonMode = true,
        )
        assertEquals(
            "https://api.experientiallabs.ai/v1/chat/completions",
            api.lastChatUrl,
        )
        // The provider contract notes extra sampling params can be rejected —
        // response_format must be omitted even in jsonMode.
        assertEquals(null, api.lastChatBody?.responseFormat)
        assertEquals("model-a", api.lastChatBody?.model)
    }

    @Test
    fun `other providers keep response_format in json mode`() = runBlocking {
        val api = RecordingApi()
        val repo = OpenAiCompatibleRepositoryImpl(api)
        repo.chat(
            connection = connection("https://api.groq.com/openai/v1", "Groq"),
            messages = listOf(OllamaMessage(OllamaRole.USER, "hi")),
            jsonMode = true,
        )
        assertEquals("json_object", api.lastChatBody?.responseFormat?.type)
    }

    @Test
    fun `openrouter gets attribution headers`() = runBlocking {
        val api = RecordingApi()
        val repo = OpenAiCompatibleRepositoryImpl(api)
        repo.listModels(connection("https://openrouter.ai/api/v1", "OpenRouter"))
        assertEquals("Ai-Cloud", api.lastModelsHeaders["X-Title"])
        assertFalse(api.lastModelsHeaders["HTTP-Referer"].isNullOrBlank())
    }

    @Test
    fun `text-only messages serialize content as plain string`() = runBlocking {
        val api = RecordingApi()
        val repo = OpenAiCompatibleRepositoryImpl(api)
        repo.chat(
            connection = connection("https://example.com/v1"),
            messages = listOf(OllamaMessage(OllamaRole.USER, "hello")),
            jsonMode = false,
        )
        val content = api.lastChatBody?.messages?.first()?.content
        assertTrue(content is kotlinx.serialization.json.JsonPrimitive)
        assertEquals("hello", (content as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `image messages become typed image_url parts with data uris`() {
        val repo = OpenAiCompatibleRepositoryImpl(RecordingApi())
        val element = repo.messageContent(
            OllamaMessage(
                role = OllamaRole.USER,
                content = "what is this?",
                images = listOf("QUJD"),
                imageMimeTypes = listOf("image/png"),
            ),
        )
        val array = element as kotlinx.serialization.json.JsonArray
        assertEquals(2, array.size)
        val textPart = array[0] as kotlinx.serialization.json.JsonObject
        assertEquals("text", textPart["type"]?.toString()?.removeSurrounding("\""))
        val imagePart = array[1] as kotlinx.serialization.json.JsonObject
        assertEquals("image_url", imagePart["type"]?.toString()?.removeSurrounding("\""))
        val imageUrl = (imagePart["image_url"] as kotlinx.serialization.json.JsonObject)["url"]
            ?.toString()?.removeSurrounding("\"")
        assertEquals("data:image/png;base64,QUJD", imageUrl)
    }

    @Test
    fun `chatStream parses sse deltas and requests stream true`() = runBlocking {
        val api = RecordingApi()
        val repo = OpenAiCompatibleRepositoryImpl(api)
        val deltas = mutableListOf<String>()
        val text = repo.chatStream(
            connection = connection("https://api.groq.com/openai/v1", "Groq"),
            messages = listOf(OllamaMessage(OllamaRole.USER, "hi")),
            jsonMode = false,
            onDelta = { deltas.add(it) },
        )
        assertEquals("ok!", text)
        assertEquals(listOf("ok", "ok!"), deltas)
        assertEquals(true, api.lastChatBody?.stream)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", api.lastChatUrl)
    }

    @Test
    fun `error body in successful response is surfaced`() = runBlocking {
        val api = RecordingApi().apply {
            // A 200 envelope can still carry an OpenAI-style error object.
        }
        val failing = object : OpenAiCompatibleApi by api {
            override suspend fun chatCompletions(
                url: String,
                body: OpenAiChatRequestDto,
                headers: Map<String, String>,
            ) = OpenAiChatResponseDto(
                choices = emptyList(),
                error = OpenAiErrorBodyDto(message = "model overloaded"),
            )
        }
        val repo = OpenAiCompatibleRepositoryImpl(failing)
        try {
            repo.chat(
                connection = connection("https://example.com/v1"),
                messages = listOf(OllamaMessage(OllamaRole.USER, "hi")),
                jsonMode = false,
            )
            org.junit.Assert.fail("expected AppError.Api")
        } catch (e: AppError.Api) {
            assertEquals("model overloaded", e.userMessage)
        }
    }
}
