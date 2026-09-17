package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilitiesTest {

    @Test
    fun `vision families are detected`() {
        val visionModels = listOf(
            "llava:13b", "gpt-4o", "gpt-4o-mini", "gemini-2.5-pro",
            "qwen2.5-vl:7b", "claude-sonnet-4", "gemma3:27b", "pixtral-12b",
        )
        visionModels.forEach { model ->
            assertTrue("expected vision: $model",
                ModelCapabilities.supports(model, ModelCapability.VISION))
        }
    }

    @Test
    fun `text-only models are not vision`() {
        val textOnly = listOf(
            "gpt-oss:120b-cloud", "deepseek-chat", "llama-3.3-70b", "mistral-7b",
        )
        textOnly.forEach { model ->
            assertFalse("expected no vision: $model",
                ModelCapabilities.supports(model, ModelCapability.VISION))
        }
    }

    @Test
    fun `image generation families`() {
        assertTrue(ModelCapabilities.supports("gpt-image-1", ModelCapability.IMAGE_GEN))
        assertTrue(ModelCapabilities.supports("dall-e-3", ModelCapability.IMAGE_GEN))
        assertTrue(ModelCapabilities.supports("flux-schnell", ModelCapability.IMAGE_GEN))
        assertFalse(ModelCapabilities.supports("gpt-4o", ModelCapability.IMAGE_GEN))
    }

    @Test
    fun `audio capabilities`() {
        assertTrue(ModelCapabilities.supports("tts-1-hd", ModelCapability.AUDIO_GEN))
        assertTrue(ModelCapabilities.supports("whisper-large-v3", ModelCapability.AUDIO_TRANSCRIBE))
        assertFalse(ModelCapabilities.supports("whisper-large-v3", ModelCapability.AUDIO_GEN))
    }

    @Test
    fun `of returns full set and labels join`() {
        val caps = ModelCapabilities.of("gpt-4o")
        assertTrue(ModelCapability.VISION in caps)
        assertTrue(ModelCapabilities.labels("gpt-4o").contains("vision"))
        assertEquals("vision", ModelCapabilities.label(ModelCapability.VISION))
    }

    @Test
    fun `case insensitive matching`() {
        assertTrue(ModelCapabilities.supports("GPT-4O-MINI", ModelCapability.VISION))
    }
}
