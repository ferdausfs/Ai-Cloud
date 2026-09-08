package dev.repochat.core.model

/**
 * Typed errors surfaced to the UI. Every repository implementation maps
 * HTTP/IO failures into one of these so the presentation layer never deals
 * with raw exceptions.
 */
sealed class AppError(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause) {

    enum class Provider(val label: String) {
        GITHUB("GitHub"),
        OLLAMA("Ollama"),
        /** Generic OpenAI-compatible cloud LLM (Groq, Cerebras, OpenRouter, …). */
        LLM("LLM"),
    }

    /** 401 — missing/invalid/expired credential. */
    class Unauthorized(val provider: Provider, message: String) : AppError(message)

    /** 403/429 — rate limited or permission denied. */
    class RateLimited(val provider: Provider, message: String) : AppError(message)

    class NotFound(message: String) : AppError(message)

    /** 409 — e.g. file changed upstream while editing. */
    class Conflict(message: String) : AppError(message)

    /** Local configuration problem (e.g. no model name set). */
    class Configuration(message: String) : AppError(message)

    /**
     * Another AI turn is already running (in a different conversation) — the
     * app supports one in-flight turn at a time. Surfaced instead of silently
     * dropping the user's message (audit finding BUG-101).
     */
    class TurnBusy(val where: String, message: String) : AppError(message)

    class Api(val provider: Provider, val code: Int?, message: String) : AppError(message)

    class Network(message: String) : AppError(message)
}
