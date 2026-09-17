package dev.repochat.core.model

/**
 * Known OpenAI-compatible provider endpoints. Base URLs verified against
 * current provider docs. [Custom] leaves [baseUrl] empty so the user can type
 * any endpoint.
 *
 * Template providers (Cloudflare Workers AI, Firebase/Vertex) contain
 * placeholders in [baseUrl] (e.g. YOUR_ACCOUNT_ID, YOUR_PROJECT_ID) that the
 * user MUST replace after selecting the preset — see [isTemplate] and the
 * [baseUrlHint] they show in the editor. These stay editable after selection.
 *
 * @param supportsJsonResponseFormat when false, the client omits the
 *   `response_format` parameter (providers whose API contract rejects extra
 *   sampling parameters still get structured JSON via the system prompt; the
 *   action parser falls back gracefully).
 * @param extraHeaders static headers appended to every request to this
 *   provider (e.g. OpenRouter's recommended attribution headers). Never
 *   contains credentials.
 */
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val supportsJsonResponseFormat: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** True when [baseUrl] carries placeholders the user must edit. */
    val isTemplate: Boolean = false,
    /** Short setup hint shown in the connection editor for template presets. */
    val baseUrlHint: String? = null,
)

/** Base URLs for template providers, with the user-replaceable parts marked. */
object ProviderTemplates {
    /** Cloudflare Workers AI — needs the account id in the path. */
    const val CLOUDFLARE_ACCOUNT = "YOUR_ACCOUNT_ID"
    /** Firebase / Vertex AI — needs project + location in the path. */
    const val VERTEX_PROJECT = "YOUR_PROJECT_ID"
    const val VERTEX_LOCATION = "us-central1"

    val CLOUDFLARE =
        "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT/ai/v1"
    val VERTEX_OPENAI =
        "https://aiplatform.googleapis.com/v1/projects/$VERTEX_PROJECT/locations/$VERTEX_LOCATION/endpoints/openapi"
}

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
    // Google AI Studio (Gemini) — official OpenAI-compatible endpoint.
    // API keys from aistudio.google.com work as the bearer token.
    ProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    // GitHub Models — the same GitHub PAT the app already stores can serve.
    ProviderPreset("GitHub Models", "https://models.github.ai/inference"),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1"),
    ProviderPreset("Mistral", "https://api.mistral.ai/v1"),
    ProviderPreset("xAI Grok", "https://api.x.ai/v1"),
    // Cloudflare Workers AI — OpenAI-compatible endpoint scoped per account.
    ProviderPreset(
        "Cloudflare Workers AI",
        ProviderTemplates.CLOUDFLARE,
        isTemplate = true,
        baseUrlHint = "Replace YOUR_ACCOUNT_ID with your Cloudflare account id (dash.cloudflare.com).",
    ),
    // Firebase / Vertex AI Gemini — OpenAI-compatible surface on Vertex.
    ProviderPreset(
        "Firebase (Vertex Gemini)",
        ProviderTemplates.VERTEX_OPENAI,
        isTemplate = true,
        baseUrlHint = "Replace YOUR_PROJECT_ID (and location if needed) — Vertex AI OpenAI-compatible endpoint.",
    ),
    // Vercel AI Gateway — one key, hundreds of models.
    ProviderPreset("Vercel AI Gateway", "https://ai-gateway.vercel.sh/v1"),
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

/**
 * Match a saved base URL to a known preset (or Custom). Template presets
 * (Cloudflare / Vertex) match by path prefix so the URL stays matched even
 * after the user replaces the placeholders with real ids.
 */
fun matchOpenAiPreset(baseUrl: String): ProviderPreset {
    val normalized = baseUrl.trim().trimEnd('/')
    if (normalized.isNotEmpty()) {
        // Exact match first (covers non-template presets and untouched templates).
        KNOWN_OPENAI_PROVIDERS.firstOrNull {
            it.baseUrl.isNotEmpty() && it.baseUrl.trimEnd('/') == normalized
        }?.let { return it }
        // Prefix match for template presets (placeholder replaced by real ids).
        KNOWN_OPENAI_PROVIDERS.firstOrNull { preset ->
            preset.isTemplate && normalized.startsWith(
                preset.baseUrl.substringBefore(placeholderMarkerIn(preset.baseUrl)).trimEnd('/'),
            )
        }?.let { return it }
    }
    return KNOWN_OPENAI_PROVIDERS.last() // Custom
}

/**
 * Returns the longest placeholder-free prefix of a template URL — e.g. for
 * Cloudflare the prefix ends right before `YOUR_ACCOUNT_ID`.
 */
private fun placeholderMarkerIn(templateUrl: String): String = when {
    templateUrl.contains("YOUR_ACCOUNT_ID") -> "YOUR_ACCOUNT_ID"
    templateUrl.contains("YOUR_PROJECT_ID") -> "YOUR_PROJECT_ID"
    else -> "›" // no marker → substringBefore returns the whole string
}
