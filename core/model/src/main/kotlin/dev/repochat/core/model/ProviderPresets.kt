package dev.repochat.core.model

/**
 * Known OpenAI-compatible provider endpoints. Base URLs verified against
 * current provider docs (Groq, Cerebras, OpenRouter, Together, Fireworks,
 * Experiential Labs). [Custom] leaves [baseUrl] empty so the user can type
 * any endpoint.
 *
 * @param supportsJsonResponseFormat when false, the client omits the
 *   `response_format` parameter (providers whose API contract rejects extra
 *   sampling parameters — e.g. Experiential Labs — still get structured JSON
 *   via the system prompt; the action parser falls back gracefully).
 * @param extraHeaders static headers appended to every request to this
 *   provider (e.g. OpenRouter's recommended attribution headers). Never
 *   contains credentials.
 */
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val supportsJsonResponseFormat: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
)

val KNOWN_OPENAI_PROVIDERS: List<ProviderPreset> = listOf(
    ProviderPreset("Groq", "https://api.groq.com/openai/v1"),
    ProviderPreset("Cerebras", "https://api.cerebras.ai/v1"),
    ProviderPreset(
        ModelPricing.OPENROUTER_LABEL,
        "https://openrouter.ai/api/v1",
        extraHeaders = mapOf(
            // OpenRouter-recommended attribution headers (optional, non-secret).
            "HTTP-Referer" to "https://github.com/ferdausfs/Ai-Cloud",
            "X-Title" to "Ai-Cloud",
        ),
    ),
    // Experiential Labs — open source AI gateway, OpenAI-compatible.
    // Docs: https://www.experientiallabs.ai ; API: /v1/chat/completions, /v1/models.
    // response_format is omitted: the provider contract notes extra sampling
    // parameters can be rejected.
    ProviderPreset(
        ModelPricing.EXPERIENTIAL_LABEL,
        "https://api.experientiallabs.ai/v1",
        supportsJsonResponseFormat = false,
    ),
    ProviderPreset("Together.ai", "https://api.together.xyz/v1"),
    ProviderPreset("Fireworks", "https://api.fireworks.ai/inference/v1"),
    ProviderPreset("Custom", ""),
)

/**
 * Curated Ollama Cloud model ids used when live listing is unavailable.
 * Names track the public cloud catalog (`:cloud` suffix).
 */
val KNOWN_OLLAMA_CLOUD_MODELS: List<String> = listOf(
    "gpt-oss:120b-cloud",
    "gpt-oss:20b-cloud",
    "gemma3:27b-cloud",
    "gemma4:31b-cloud",
    "nemotron-3-nano:30b-cloud",
    "nemotron-3-super:cloud",
    "nemotron-3-ultra:cloud",
    "qwen3.5:cloud",
    "minimax-m2.7:cloud",
)

/**
 * Curated Experiential Labs model ids used ONLY as a fallback when live
 * `GET /v1/models` listing is unavailable. Sourced from provider docs
 * (September 2026); the catalog can change at any time — the app never
 * assumes these specific ids keep existing and always prefers live listing.
 * `gpt-6-astra` / `claude-fable-5.1` currently carry the promotional free
 * daily tier; `gpt-5.6-sol` is a paid model with a flex rate.
 */
val KNOWN_EXPERIENTIAL_MODELS: List<String> = listOf(
    "gpt-6-astra",
    "claude-fable-5.1",
    "gpt-5.6-sol",
)

/** Match a saved base URL to a known preset (or Custom). */
fun matchOpenAiPreset(baseUrl: String): ProviderPreset {
    val normalized = baseUrl.trim().trimEnd('/')
    return KNOWN_OPENAI_PROVIDERS.firstOrNull {
        it.baseUrl.isNotEmpty() && it.baseUrl.trimEnd('/') == normalized
    } ?: KNOWN_OPENAI_PROVIDERS.last() // Custom
}
