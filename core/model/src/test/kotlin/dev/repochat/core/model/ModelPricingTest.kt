package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPricingTest {

    @Test
    fun `openrouter colon-free ids are FREE and other ids are PAID`() {
        assertEquals(
            ModelPriceClass.FREE,
            ModelPricing.classify("meta-llama/llama-3.1-8b-instruct:free", "OpenRouter"),
        )
        assertEquals(
            ModelPriceClass.FREE,
            ModelPricing.classify("meta-llama/llama-3.1-8b-instruct:FREE", "OpenRouter"),
        )
        // OpenRouter bills non-:free routes at provider price — factual, not a guess.
        assertEquals(
            ModelPriceClass.PAID,
            ModelPricing.classify("openai/gpt-5.6", "OpenRouter"),
        )
    }

    @Test
    fun `experiential known promotional slugs are PROMOTIONAL`() {
        assertEquals(
            ModelPriceClass.PROMOTIONAL,
            ModelPricing.classify("gpt-6-astra", "Experiential Labs"),
        )
        assertEquals(
            ModelPriceClass.PROMOTIONAL,
            ModelPricing.classify("claude-fable-5.1", "Experiential Labs"),
        )
    }

    @Test
    fun `experiential unknown slugs are UNKNOWN - never invented pricing`() {
        assertEquals(
            ModelPriceClass.UNKNOWN,
            ModelPricing.classify("deepseek-v4-flash", "Experiential Labs"),
        )
        assertEquals(
            ModelPriceClass.UNKNOWN,
            ModelPricing.classify("qwen3.8-27b", "Experiential Labs"),
        )
    }

    @Test
    fun `ollama and unknown providers stay UNKNOWN`() {
        assertEquals(
            ModelPriceClass.UNKNOWN,
            ModelPricing.classify("gpt-oss:120b-cloud", "Ollama"),
        )
        assertEquals(
            ModelPriceClass.UNKNOWN,
            ModelPricing.classify("some-model", "Groq"),
        )
        assertEquals(
            ModelPriceClass.UNKNOWN,
            ModelPricing.classify("some-model", "Custom"),
        )
    }

    @Test
    fun `blank id is UNKNOWN`() {
        assertEquals(ModelPriceClass.UNKNOWN, ModelPricing.classify("", "OpenRouter"))
        assertEquals(ModelPriceClass.UNKNOWN, ModelPricing.classify("   ", "OpenRouter"))
    }

    @Test
    fun `isFreeTier covers FREE and PROMOTIONAL only`() {
        assertTrue(ModelPricing.isFreeTier("a:free", "OpenRouter"))
        assertTrue(ModelPricing.isFreeTier("gpt-6-astra", "Experiential Labs"))
        assertFalse(ModelPricing.isFreeTier("openai/gpt-5.6", "OpenRouter"))
        assertFalse(ModelPricing.isFreeTier("deepseek-v4-flash", "Experiential Labs"))
    }

    @Test
    fun `sortFreeFirst ranks free then promotional then paid then unknown`() {
        val sorted = ModelPricing.sortFreeFirst(
            listOf(
                "zz-unknown",
                "b-model:free",
                "m-paid",
                "gpt-6-astra",
                "a-model:free",
            ),
            "Experiential Labs",
        )
        // FREE group (alpha), PROMOTIONAL group, then UNKNOWN (alpha).
        assertEquals(
            listOf("a-model:free", "b-model:free", "gpt-6-astra", "m-paid", "zz-unknown"),
            sorted,
        )
    }

    @Test
    fun `sortFreeFirst is provider-aware - same id ranks differently per provider`() {
        // "gpt-6-astra" is promotional on Experiential but UNKNOWN on a custom endpoint.
        val onExperiential = ModelPricing.sortFreeFirst(
            listOf("gpt-6-astra", "other"),
            "Experiential Labs",
        )
        assertEquals("gpt-6-astra", onExperiential.first())
        val onCustom = ModelPricing.sortFreeFirst(
            listOf("gpt-6-astra", "other"),
            "Custom",
        )
        assertEquals(listOf("gpt-6-astra", "other"), onCustom)
    }

    @Test
    fun `badge labels are short and honest`() {
        assertEquals("FREE", ModelPricing.badgeLabel(ModelPriceClass.FREE))
        assertEquals("PROMO", ModelPricing.badgeLabel(ModelPriceClass.PROMOTIONAL))
        assertEquals("PAID", ModelPricing.badgeLabel(ModelPriceClass.PAID))
        assertEquals("N/A", ModelPricing.badgeLabel(ModelPriceClass.UNKNOWN))
    }
}
