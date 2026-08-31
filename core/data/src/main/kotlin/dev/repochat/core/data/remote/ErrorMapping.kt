package dev.repochat.core.data.remote

import dev.repochat.core.model.AppError
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
            AppError.Provider.LLM -> {
                try {
                    val dto = errorJson.decodeFromString(OpenAiErrorDto.serializer(), body)
                    dto.error?.message ?: dto.message
                } catch (_: Exception) {
                    null
                }
            }
            AppError.Provider.CLOUDFLARE -> {
                try {
                    val dto = errorJson.decodeFromString(CloudflareErrorBodyDto.serializer(), body)
                    dto.errors.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
                        ?: dto.message
                } catch (_: Exception) {
                    null
                }
            }
            AppError.Provider.VERCEL -> {
                try {
                    val dto = errorJson.decodeFromString(VercelErrorWrapperDto.serializer(), body)
                    dto.error?.message ?: dto.message
                } catch (_: Exception) {
                    null
                }
            }
            AppError.Provider.FIREBASE -> {
                try {
                    val dto = errorJson.decodeFromString(FirebaseApiErrorDto.serializer(), body)
                    val raw = dto.error
                    // REST errors: `error:{message:…}`; OAuth errors:
                    // `error:"invalid_grant", error_description:…` (prefer description).
                    (raw as? JsonObject)?.get("message")?.let {
                        (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                    }
                        ?: dto.errorDescription?.takeIf { it.isNotBlank() }
                        ?: (raw as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        ?: dto.message
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

    val unauthorizedMessage = when (provider) {
        AppError.Provider.GITHUB ->
            "Your GitHub token was rejected — it is invalid or expired. Update it in Settings."
        AppError.Provider.OLLAMA ->
            "Your Ollama API key was rejected. Check it in Settings."
        AppError.Provider.LLM ->
            "Your LLM API key was rejected. Check it in Settings."
        AppError.Provider.CLOUDFLARE ->
            "Cloudflare rejected your API token or account id. Check them in Settings."
        AppError.Provider.VERCEL ->
            "Vercel rejected your API token. Check it in Settings."
        AppError.Provider.FIREBASE ->
            "Firebase/Google rejected the credential. Check the API key or Service Account JSON in Settings."
    }
    val forbiddenMessage = when (provider) {
        AppError.Provider.GITHUB ->
            "GitHub API rate limit reached — try again in a few minutes."
        AppError.Provider.OLLAMA ->
            "Ollama rejected the request (403). Check your API key scope in Settings."
        AppError.Provider.LLM ->
            "LLM provider rejected the request (403). Check key/quota in Settings."
        AppError.Provider.CLOUDFLARE ->
            "Cloudflare denied the request (403). Check the token permissions / Account ID."
        AppError.Provider.VERCEL ->
            "Vercel denied the request (403). Check the token scope in Settings."
        AppError.Provider.FIREBASE ->
            "Firebase/Google denied the request (403). Check project permissions in Settings."
    }
    val rateLimitMessage = when (provider) {
        AppError.Provider.OLLAMA ->
            "You've hit the Ollama rate limit. The free tier allows a limited number of requests per minute — wait a moment and retry."
        AppError.Provider.LLM ->
            "LLM provider rate limit reached. The app can fall back to the next configured provider."
        AppError.Provider.GITHUB ->
            "GitHub API rate limit reached — try again in a few minutes."
        AppError.Provider.CLOUDFLARE ->
            "Cloudflare API rate limit reached — try again in a few minutes."
        AppError.Provider.VERCEL ->
            "Vercel API rate limit reached — try again in a few minutes."
        AppError.Provider.FIREBASE ->
            "Firebase/Google API rate limit reached — try again in a few minutes."
    }

    return when (code) {
        401 -> AppError.Unauthorized(provider, unauthorizedMessage)
        403 -> AppError.RateLimited(provider, forbiddenMessage)
        404 -> AppError.NotFound(detail)
        409 -> AppError.Conflict(
            "The file changed on GitHub since it was last read. Send the message again so the latest version is re-read first."
        )
        429 -> AppError.RateLimited(provider, rateLimitMessage)
        else -> AppError.Api(provider, code, detail)
    }
}
