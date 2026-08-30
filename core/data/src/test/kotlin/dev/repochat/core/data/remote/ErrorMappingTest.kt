package dev.repochat.core.data.remote

import dev.repochat.core.model.AppError
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ErrorMappingTest {

    private fun httpException(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody(null)))

    /**
     * Builds an HttpException whose [HttpException.message] is blank — the
     * real HTTP/2 case. Retrofit's [Response.error] helper always sets
     * message to `"Response.error()"`, so it cannot reproduce the bug.
     */
    private fun httpExceptionBlankReason(code: Int, body: String = ""): HttpException {
        val responseBody = body.toResponseBody("application/json".toMediaTypeOrNull())
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://example.test/").build())
            .protocol(Protocol.HTTP_2)
            .code(code)
            .message("") // blank reason phrase, as HTTP/2 serves
            .body(responseBody)
            .build()
        return HttpException(Response.error<Any>(responseBody, raw))
    }

    @Test
    fun `401 maps to unauthorized with actionable message`() {
        val error = toAppError(AppError.Provider.GITHUB, httpException(401))
        assertTrue(error is AppError.Unauthorized)
        assertTrue(error.userMessage.contains("Settings"))
    }

    @Test
    fun `429 maps to rate limited with friendly message`() {
        val error = toAppError(AppError.Provider.OLLAMA, httpException(429))
        assertTrue(error is AppError.RateLimited)
        assertTrue(error.userMessage.contains("rate limit", ignoreCase = true))
    }

    @Test
    fun `404 maps to not found`() {
        assertTrue(toAppError(AppError.Provider.GITHUB, httpException(404)) is AppError.NotFound)
    }

    @Test
    fun `409 maps to conflict with re-read guidance`() {
        val error = toAppError(AppError.Provider.GITHUB, httpException(409))
        assertTrue(error is AppError.Conflict)
        assertTrue(error.userMessage.contains("re-read", ignoreCase = true))
    }

    @Test
    fun `github error body message is surfaced for unknown codes`() {
        val error = toAppError(
            AppError.Provider.GITHUB,
            httpException(500, """{"message":"internal server error"}"""),
        )
        assertEquals(500, (error as AppError.Api).code)
        assertEquals("internal server error", error.userMessage)
    }

    @Test
    fun `blank HTTP reason phrase falls back to HTTP code`() {
        val error = toAppError(AppError.Provider.OLLAMA, httpExceptionBlankReason(502))
        assertTrue(error is AppError.Api)
        assertEquals("HTTP 502", error.userMessage)
    }

    @Test
    fun `blank API message body falls back without empty banner text`() {
        // Blank API message + blank reason phrase must not produce an empty banner.
        val error = toAppError(
            AppError.Provider.GITHUB,
            httpExceptionBlankReason(500, """{"message":"   "}"""),
        )
        assertTrue("Expected non-blank, got '${error.userMessage}'", error.userMessage.isNotBlank())
        assertEquals("HTTP 500", error.userMessage)
    }
}
