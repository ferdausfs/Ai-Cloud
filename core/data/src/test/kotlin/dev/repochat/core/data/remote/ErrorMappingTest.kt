package dev.repochat.core.data.remote

import dev.repochat.core.model.AppError
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ErrorMappingTest {

    private fun httpException(code: Int, body: String = ""): HttpException =
        HttpException(Response.error<Any>(code, body.toResponseBody(null)))

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
}
