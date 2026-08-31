package dev.repochat.core.model

/**
 * Known OpenAI-compatible provider endpoints. Base URLs verified against
 * current provider docs (Groq, Cerebras, OpenRouter, Together, Fireworks).
 * [Custom] leaves [baseUrl] empty so the user can type any endpoint.
 */
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
)

val KNOWN_OPENAI_PROVIDERS: List<ProviderPreset> = listOf(
    ProviderPreset("Groq", "https://api.groq.com/openai/v1"),
    ProviderPreset("Cerebras", "https://api.cerebras.ai/v1"),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1"),
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

/** Match a saved base URL to a known preset (or Custom). */
fun matchOpenAiPreset(baseUrl: String): ProviderPreset {
    val normalized = baseUrl.trim().trimEnd('/')
    return KNOWN_OPENAI_PROVIDERS.firstOrNull {
        it.baseUrl.isNotEmpty() && it.baseUrl.trimEnd('/') == normalized
    } ?: KNOWN_OPENAI_PROVIDERS.last() // Custom
}
