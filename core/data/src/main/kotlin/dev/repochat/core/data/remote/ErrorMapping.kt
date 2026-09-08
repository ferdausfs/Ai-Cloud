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
    val retryAfter: String? = try {
        e.response()?.headers()?.get("Retry-After")?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
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
            AppError.Provider.LLM -> {
                try {
                    val dto = errorJson.decodeFromString(OpenAiErrorDto.serializer(), body)
                    dto.error?.message ?: dto.message
                } catch (_: Exception) {
                    null
                }
            }
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
    val retryHint = retryAfter?.let { seconds ->
        seconds.toLongOrNull()?.let { secs ->
            if (secs > 0) " Try again in ~$secs second${if (secs == 1L) "" else "s"}." else null
        } ?: " Try again shortly."
    } ?: ""

    return when (code) {
        401 -> AppError.Unauthorized(
            provider,
            when (provider) {
                AppError.Provider.GITHUB ->
                    "Your GitHub token was rejected — it is invalid or expired. Update it in Settings."
                AppError.Provider.OLLAMA ->
                    "Your Ollama API key was rejected. Check it in Settings."
                AppError.Provider.LLM ->
                    "Your LLM API key was rejected. Check it in Settings."
            },
        )
        402 -> AppError.RateLimited(
            provider,
            when (provider) {
                AppError.Provider.LLM ->
                    "The provider requires payment verification for this model " +
                        "(HTTP 402). Add a payment method on the provider's dashboard, " +
                        "switch to a free/promotional model, or use a different provider."
                AppError.Provider.GITHUB ->
                    "GitHub requires payment for this request (HTTP 402)."
                AppError.Provider.OLLAMA ->
                    "Ollama requires payment verification for this request (HTTP 402)."
            },
        )
        403 -> AppError.RateLimited(
            provider,
            when (provider) {
                AppError.Provider.GITHUB ->
                    "GitHub API rate limit reached — try again in a few minutes."
                AppError.Provider.OLLAMA ->
                    "Ollama rejected the request (403). Check your API key scope in Settings."
                AppError.Provider.LLM ->
                    "LLM provider rejected the request (403). Check key/quota in Settings."
            },
        )
        404 -> AppError.NotFound(detail)
        409 -> AppError.Conflict(
            "The file changed on GitHub since it was last read. Send the message again so the latest version is re-read first."
        )
        429 -> AppError.RateLimited(
            provider,
            when (provider) {
                AppError.Provider.OLLAMA ->
                    "You've hit the Ollama rate limit. The free tier allows a limited number of requests per minute — wait a moment and retry.$retryHint"
                // Include the provider's own detail (e.g. "insufficient_quota: " +
                // "free_limit_reached") so daily/hourly free-tier limits are
                // recognizable instead of a generic throttled message.
                AppError.Provider.LLM ->
                    buildString {
                        append("LLM provider rate limit reached.")
                        if (apiMessage?.isNotBlank() == true) {
                            append(" Detail: ")
                            append(apiMessage.take(240))
                        }
                        append(
                            " The app can fall back to the next configured provider, " +
                                "or the free tier resets later.",
                        )
                        append(retryHint)
                    }
                AppError.Provider.GITHUB ->
                    "GitHub API rate limit reached — try again in a few minutes.$retryHint"
            },
        )
        else -> AppError.Api(provider, code, detail)
    }
}
