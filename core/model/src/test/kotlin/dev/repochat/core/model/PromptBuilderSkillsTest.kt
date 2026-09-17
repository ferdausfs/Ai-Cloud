package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderSkillsTest {

    private fun skill(name: String, body: String, description: String = "does $name") =
        InstalledSkill(
            name = name,
            description = description,
            instructions = body,
            sourceRepo = "acme/skills",
            sourcePath = "$name/SKILL.md",
        )

    @Test
    fun `empty skills leaves prompt untouched`() {
        assertEquals(PromptBuilder.system(), PromptBuilder.withAgentSkills(PromptBuilder.system(), emptyList()))
        assertEquals(
            PromptBuilder.generalSystem(),
            PromptBuilder.withGeneralSkills(PromptBuilder.generalSystem(), emptyList()),
        )
    }

    @Test
    fun `small skills are inlined with instructions`() {
        val out = PromptBuilder.withAgentSkills(
            PromptBuilder.system(),
            listOf(skill("pdf", "Extract text with pdftotext.")),
        )
        assertTrue(out.contains("AVAILABLE SKILLS"))
        assertTrue(out.contains("name: pdf"))
        assertTrue(out.contains("BEGIN SKILL INSTRUCTIONS"))
        assertTrue(out.contains("Extract text with pdftotext."))
        // The load-on-demand instruction is not needed when everything is inlined
        assertFalse(out.contains("Call read_skill to load"))
        // base system prompt preserved at the start
        assertTrue(out.startsWith("You are Ai Cloud"))
    }

    @Test
    fun `oversized skill is deferred to read_skill`() {
        // Per-skill inline budget is 6_000 chars; anything larger is offered
        // through the read_skill action instead.
        val big = "x".repeat(30_000)
        val out = PromptBuilder.withAgentSkills(
            PromptBuilder.system(),
            listOf(skill("big-skill", big)),
        )
        assertTrue(out.contains("read_skill"))
        assertTrue(out.contains("name: big-skill"))
        assertFalse(out.contains("BEGIN SKILL INSTRUCTIONS"))
    }

    @Test
    fun `general mode inlines bodies with cap and follow rule`() {
        val big = "y".repeat(20_000)
        val out = PromptBuilder.withGeneralSkills(
            PromptBuilder.generalSystem(),
            listOf(skill("big", big), skill("small", "Short body.")),
        )
        assertTrue(out.contains("INSTALLED SKILLS"))
        assertTrue(out.contains("Short body."))
        assertTrue(out.contains("(skill instructions truncated to fit the context)"))
        assertTrue(out.length < big.length + 6_000)
    }

    @Test
    fun `skill content and not-found messages`() {
        val content = PromptBuilder.skillContentMessage(skill("pdf", "Body here."))
        assertTrue(content.contains("SKILL LOADED - pdf"))
        assertTrue(content.contains("Body here."))

        val missing = PromptBuilder.skillNotFoundMessage("nope", listOf("a", "b"))
        assertTrue(missing.contains("SKILL NOT FOUND"))
        assertTrue(missing.contains("a, b"))
    }

    @Test
    fun `greeting lists skills and media capabilities`() {
        val out = PromptBuilder.greetingMessage(
            listOf(skill("pdf", body = "x", description = "Handles PDFs.")),
            setOf(ModelCapability.IMAGE_GEN, ModelCapability.AUDIO_GEN),
        )
        assertTrue(out.contains("Ai Cloud agent"))
        assertTrue(out.contains("Installed skills:"))
        assertTrue(out.contains("pdf — Handles PDFs."))
        assertTrue(out.contains("Generate images"))
        assertTrue(out.contains("Read my replies aloud"))
        assertTrue(out.contains("What should we do first?"))
    }

    @Test
    fun `greeting without skills or media stays short`() {
        val out = PromptBuilder.greetingMessage(emptyList(), emptySet())
        assertFalse(out.contains("Installed skills:"))
        assertFalse(out.contains("Generate images"))
        assertTrue(out.contains("attach one from the top menu"))
    }
}
