package dev.repochat.core.data.repository

import dev.repochat.core.data.remote.OllamaApi
import dev.repochat.core.data.remote.OllamaChatRequestDto
import dev.repochat.core.data.remote.OllamaVersionDto
import dev.repochat.core.model.AppError
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.OllamaRole
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OllamaRepositoryImplTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private fun body(text: String) =
        text.toResponseBody("application/x-ndjson".toMediaType())

    private fun repo(raw: String) = OllamaRepositoryImpl(
        api = object : OllamaApi {
            override suspend fun version(): OllamaVersionDto = OllamaVersionDto("test")
            override suspend fun chat(body: OllamaChatRequestDto) = body(raw)
        },
        json = json,
    )

    @Test
    fun `concatenates NDJSON content deltas in order`() = runBlocking {
        val ndjson = """
            {"model":"m","message":{"role":"assistant","content":"{\"action\""},"done":false}
            {"model":"m","message":{"role":"assistant","content":":\"reply\","},"done":false}
            {"model":"m","message":{"role":"assistant","content":"\"message\":\"hi\"}"},"done":true}
        """.trimIndent()

        val result = repo(ndjson).chat("m", listOf(OllamaMessage(OllamaRole.USER, "hi")))
        assertEquals("""{"action":"reply","message":"hi"}""", result)
    }

    @Test
    fun `single JSON object still works`() = runBlocking {
        val single = """{"message":{"role":"assistant","content":"{\"action\":\"reply\",\"message\":\"ok\"}"},"done":true}"""
        val result = repo(single).chat("m", listOf(OllamaMessage(OllamaRole.USER, "hi")))
        assertEquals("""{"action":"reply","message":"ok"}""", result)
    }

    @Test
    fun `chunk error field surfaces as AppError`() = runBlocking {
        val ndjson = """
            {"error":"model 'x' not found"}
        """.trimIndent()
        try {
            repo(ndjson).chat("x", emptyList())
            fail("expected AppError")
        } catch (e: AppError.Api) {
            assertTrue(e.userMessage.contains("not found"))
        }
    }
}
