package dev.repochat.core.model

/**
 * The single structured JSON contract the LLM is asked to follow.
 * Every model response is parsed into one of these actions; anything that
 * does not parse falls back to a plain-text [AiAction.Reply].
 */
sealed interface AiAction {
    data class Reply(val text: String) : AiAction
    data class ReadFile(val path: String) : AiAction
    data class WriteFile(val path: String, val content: String, val commitMessage: String) : AiAction
}

enum class OllamaRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

/**
 * One chat turn for the Ollama API. [images] holds base64-encoded image
 * payloads (no data-URI prefix) for vision-capable models; leave null for
 * text-only turns.
 */
data class OllamaMessage(
    val role: OllamaRole,
    val content: String,
    val images: List<String>? = null,
)

object AiActionParser {

    @kotlinx.serialization.Serializable
    private data class ActionDto(
        val action: String = "",
        val path: String = "",
        val content: String = "",
        @kotlinx.serialization.SerialName("commit_message") val commitMessage: String = "",
        val message: String = "",
    )

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val fencedJson = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

    fun parse(raw: String): AiAction {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return AiAction.Reply("The model returned an empty response. Please try again.")
        }
        val cleaned = fencedJson.find(trimmed)
            ?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: trimmed

        val dto = try {
            json.decodeFromString(ActionDto.serializer(), cleaned)
        } catch (_: Exception) {
            return AiAction.Reply(fallbackText(raw))
        }

        return when (dto.action.trim().lowercase()) {
            "read_file" -> {
                val path = sanitizePath(dto.path)
                if (path == null) AiAction.Reply(fallbackText(raw))
                else AiAction.ReadFile(path)
            }
            "write_file" -> {
                val path = sanitizePath(dto.path)
                if (path == null || dto.content.isBlank()) {
                    AiAction.Reply(fallbackText(raw))
                } else {
                    val commit = dto.commitMessage.trim().take(200)
                        .ifEmpty { "chore: update ${path.substringAfterLast('/')}" }
                    AiAction.WriteFile(path, dto.content, commit)
                }
            }
            else -> {
                val message = dto.message.trim()
                if (message.isNotEmpty()) AiAction.Reply(message)
                else AiAction.Reply(fallbackText(raw))
            }
        }
    }

    /** Normalizes a model-provided path to a safe, repo-relative path (or null). */
    fun sanitizePath(path: String): String? {
        var p = path.trim().replace('\\', '/')
        while (p.startsWith("./")) p = p.removePrefix("./")
        while (p.startsWith('/')) p = p.removePrefix("/")
        if (p.isBlank() || p.length > 512) return null
        val segments = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty() || segments.any { it == ".." || it == ".git" }) return null
        return segments.joinToString("/")
    }

    private fun fallbackText(raw: String): String {
        val text = raw.trim().take(4_000)
        return if (text.isNotEmpty()) {
            "I had trouble producing a structured response, so here it is as plain text:\n\n$text"
        } else {
            "I had trouble producing a structured response. Could you rephrase your request?"
        }
    }
}
