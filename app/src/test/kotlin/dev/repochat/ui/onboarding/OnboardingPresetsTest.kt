package dev.repochat.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPresetsTest {

    @Test
    fun `curated list contains the expected providers without templates`() {
        val labels = OnboardingPresets.curated().map { it.label }
        assertEquals(
            listOf("Groq", "OpenRouter", "Google Gemini", "Cerebras", "GitHub Models", "Custom"),
            labels,
        )
        assertTrue(labels.none { it.contains("Cloudflare") || it.contains("Firebase") })
    }

    @Test
    fun `suggested model exists for every curated preset`() {
        OnboardingPresets.curated().forEach { preset ->
            val model = OnboardingPresets.suggestedModel(preset.label)
            if (preset.label != "Custom") {
                assertNotNull("missing suggestion for ${preset.label}", model)
                assertTrue(model.isNotBlank())
            }
        }
    }

    @Test
    fun `unknown preset label falls back to Custom`() {
        assertEquals("Custom", OnboardingPresets.preset("Nope").label)
        assertEquals("", OnboardingPresets.suggestedModel("Nope"))
    }
}
