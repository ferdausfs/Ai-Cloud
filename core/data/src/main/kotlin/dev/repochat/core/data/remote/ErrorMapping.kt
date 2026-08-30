package dev.repochat.core.data.remote

import dev.repochat.core.model.AppError
import java.io.IOException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

private val errorJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Executes a Retrofit call and converts transport failures into typed
 * [AppError]s so the UI can present precise, actionable messages.
 */
internal suspend fun <T> mapHttpErrors(
    provider: AppError.Provider,
    block: suspend () -> T,
): T = try {
    block()
} catch (e: HttpException) {
    throw toAppError(provider, e)
} catch (e: IOException) {
    throw AppError.Network(
        "No network connection. Check your connection and try again."
    )
}

internal fun toAppError(provider: AppError.Provider, e: HttpException): AppError {
    val code = e.code()
    val apiMessage = try {
        val body = e.response()?.errorBody()?.string().orEmpty()
        when (provider) {
            AppError.Provider.GITHUB -> {
                // Prefer nested errors[].message (e.g. 422 "No commits between…")
                // over the generic top-level "Validation Failed".
                val dto = errorJson.decodeFromString(GithubErrorDto.serializer(), body)
                dto.errors?.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
                    ?: dto.message
            }
            AppError.Provider.OLLAMA ->
                errorJson.decodeFromString(OllamaErrorDto.serializer(), body).error
        }
    } catch (_: Exception) {
        null
    }
    // HttpException.message() returns the HTTP reason phrase. HTTP/2 (used by
    // GitHub and Ollama) has no reason phrase, so message() is "" — not null.
    // Guard blank as well as null so the banner never shows empty text.
    val detail = apiMessage?.takeIf { it.isNotBlank() }
        ?: e.message()?.takeIf { it.isNotBlank() }
        ?: "HTTP $code"

    return when (code) {
        401 -> AppError.Unauthorized(
            provider,
            if (provider == AppError.Provider.GITHUB) {
                "Your GitHub token was rejected — it is invalid or expired. Update it in Settings."
            } else {
                "Your Ollama API key was rejected. Check it in Settings."
            },
        )
        403 -> AppError.RateLimited(
            provider,
            if (provider == AppError.Provider.GITHUB) {
                "GitHub API rate limit reached — try again in a few minutes."
            } else {
                "Ollama rejected the request (403). Check your API key scope in Settings."
            },
        )
        404 -> AppError.NotFound(detail)
        409 -> AppError.Conflict(
            "The file changed on GitHub since it was last read. Send the message again so the latest version is re-read first."
        )
        429 -> AppError.RateLimited(
            provider,
            if (provider == AppError.Provider.OLLAMA) {
                "You've hit the Ollama rate limit. The free tier allows a limited number of requests per minute — wait a moment and retry."
            } else {
                "GitHub API rate limit reached — try again in a few minutes."
            },
        )
        else -> AppError.Api(provider, code, detail)
    }
}
