package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test
    fun `known providers include Groq with openai v1 path`() {
        val groq = KNOWN_OPENAI_PROVIDERS.first { it.label == "Groq" }
        assertEquals("https://api.groq.com/openai/v1", groq.baseUrl)
    }

    @Test
    fun `Custom preset has empty base url`() {
        val custom = KNOWN_OPENAI_PROVIDERS.last()
        assertEquals("Custom", custom.label)
        assertEquals("", custom.baseUrl)
    }

    @Test
    fun `matchOpenAiPreset normalizes trailing slash`() {
        val matched = matchOpenAiPreset("https://api.cerebras.ai/v1/")
        assertEquals("Cerebras", matched.label)
    }

    @Test
    fun `unknown base url maps to Custom`() {
        val matched = matchOpenAiPreset("https://example.com/v1")
        assertEquals("Custom", matched.label)
    }

    @Test
    fun `ollama cloud models include gpt-oss cloud ids`() {
        assertTrue(KNOWN_OLLAMA_CLOUD_MODELS.any { it.startsWith("gpt-oss:") })
        assertTrue(KNOWN_OLLAMA_CLOUD_MODELS.any { "nemotron" in it })
    }

    @Test
    fun `experiential labs preset points at official v1 endpoint`() {
        val experiential = KNOWN_OPENAI_PROVIDERS.first { it.label == ModelPricing.EXPERIENTIAL_LABEL }
        assertEquals("https://api.experientiallabs.ai/v1", experiential.baseUrl)
        // The provider contract notes extra sampling parameters can be rejected.
        assertFalse(experiential.supportsJsonResponseFormat)
    }

    @Test
    fun `matchOpenAiPreset matches experiential with trailing slash`() {
        val matched = matchOpenAiPreset("https://api.experientiallabs.ai/v1/")
        assertEquals(ModelPricing.EXPERIENTIAL_LABEL, matched.label)
        assertEquals("https://api.experientiallabs.ai/v1", matched.baseUrl)
    }

    @Test
    fun `groq keeps response_format support and no extra headers`() {
        val groq = KNOWN_OPENAI_PROVIDERS.first { it.label == "Groq" }
        assertTrue(groq.supportsJsonResponseFormat)
        assertTrue(groq.extraHeaders.isEmpty())
    }
}
