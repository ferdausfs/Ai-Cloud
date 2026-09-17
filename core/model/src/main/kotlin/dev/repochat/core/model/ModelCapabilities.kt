package dev.repochat.core.model

import java.util.EnumSet

/**
 * What a model can do, inferred from its name via curated marker lists.
 * Conservative by design: a miss degrades gracefully (image described in
 * text, generation feature hidden), while a false positive would 400 the
 * request — so markers only include model families documented to support
 * the capability on OpenAI-compatible endpoints.
 */
enum class ModelCapability {
    /** Accepts image input (chat with screenshots/photos). */
    VISION,

    /** Generates images from text (separate images/generations call). */
    IMAGE_GEN,

    /** Generates speech / audio from text (TTS). */
    AUDIO_GEN,

    /** Transcribes audio to text (STT). */
    AUDIO_TRANSCRIBE,
}

object ModelCapabilities {

    fun supports(modelName: String, capability: ModelCapability): Boolean =
        when (capability) {
            ModelCapability.VISION -> matches(modelName, VISION_MARKERS)
            ModelCapability.IMAGE_GEN -> matches(modelName, IMAGE_GEN_MARKERS)
            ModelCapability.AUDIO_GEN -> matches(modelName, AUDIO_GEN_MARKERS)
            ModelCapability.AUDIO_TRANSCRIBE -> matches(modelName, TRANSCRIBE_MARKERS)
        }

    fun of(modelName: String): Set<ModelCapability> =
        ModelCapability.entries.filterTo(EnumSet.noneOf(ModelCapability::class.java)) {
            supports(modelName, it)
        }

    /** Short label for the model picker rows, e.g. "vision · image-gen". */
    fun labels(modelName: String): String = of(modelName).joinToString(" · ") { label(it) }

    fun label(capability: ModelCapability): String = when (capability) {
        ModelCapability.VISION -> "vision"
        ModelCapability.IMAGE_GEN -> "image-gen"
        ModelCapability.AUDIO_GEN -> "audio"
        ModelCapability.AUDIO_TRANSCRIBE -> "transcribe"
    }

    private fun matches(modelName: String, markers: List<String>): Boolean {
        val n = modelName.lowercase()
        return markers.any { n.contains(it) }
    }

    private val VISION_MARKERS = listOf(
        // Ollama / open models
        "llava", "vision", "bakllava", "moondream", "minicpm-v",
        "qwen2-vl", "qwen2.5-vl", "qwen3-vl", "qwen-vl", "gemma3", "pixtral",
        "glm-4v", "llama-3.2-11b", "llama-3.2-90b",
        // Cloud / proprietary
        "gpt-4o", "gpt-4.1", "gpt-5", "gpt-4-turbo", "o3", "o4-",
        "claude-3", "claude-4", "claude-sonnet", "claude-opus", "claude-haiku",
        "claude-fable", "gemini", "grok-4",
        "grok-vision", "pixtral", "internvl", "phi-3.5-vision", "phi-4-multimodal",
    )

    private val IMAGE_GEN_MARKERS = listOf(
        "dall-e", "dalle", "gpt-image", "imagen", "flux", "stable-diffusion",
        "sdxl", "sd3", "janus", "recraft", "grok-image", "seedream",
    )

    private val AUDIO_GEN_MARKERS = listOf(
        "tts-1", "tts-2", "gpt-4o-mini-tts", "audio-speech", "kokoro", "piper",
        "elevenlabs", "csm-", "sesame", "orpheus",
    )

    private val TRANSCRIBE_MARKERS = listOf(
        "whisper", "voxtral", "deepgram", "parakeet", "canary", "gpt-4o-transcribe",
    )
}
