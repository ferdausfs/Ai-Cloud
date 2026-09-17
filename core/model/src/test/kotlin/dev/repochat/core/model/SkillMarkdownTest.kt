package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMarkdownTest {

    @Test
    fun `parses standard claude skill`() {
        val raw = """
            ---
            name: pdf-processing
            description: Extract text and tables from PDF files and fill forms.
            license: MIT
            allowed-tools: Bash(pdftotext:*)
            ---

            # PDF processing

            Use pdftotext first. Never guess table columns.
        """.trimIndent()

        val skill = SkillMarkdown.parse(raw, fallbackName = "ignored")!!

        assertEquals("pdf-processing", skill.name)
        assertEquals("Extract text and tables from PDF files and fill forms.", skill.description)
        assertEquals("MIT", skill.license)
        assertEquals("Bash(pdftotext:*)", skill.allowedTools)
        assertTrue(skill.instructions.contains("# PDF processing"))
        assertTrue(skill.instructions.contains("Never guess table columns."))
        // Frontmatter must not leak into the body.
        assertFalse(skill.instructions.contains("description:"))
    }

    @Test
    fun `falls back to fallback name and normalizes it`() {
        val raw = """
            ---
            description: Builds release APKs and verifies signatures.
            ---
            Step 1: run gradle.
        """.trimIndent()

        val skill = SkillMarkdown.parse(raw, fallbackName = "Release Builder!")!!

        assertEquals("release-builder", skill.name)
        assertTrue(skill.instructions.startsWith("Step 1: run gradle."))
    }

    @Test
    fun `handles quoted values and unknown keys`() {
        val raw = """
            ---
            name: "code-review"
            description: 'Reviews diffs for regressions.'
            version: 3
            unknown-future-key: whatever
            ---
            Review rules here.
        """.trimIndent()

        val skill = SkillMarkdown.parse(raw, fallbackName = "x")!!

        assertEquals("code-review", skill.name)
        assertEquals("Reviews diffs for regressions.", skill.description)
    }

    @Test
    fun `rejects file without usable name`() {
        val raw = "Just some random notes without structure."
        assertNull(SkillMarkdown.parse(raw, fallbackName = "###"))
    }

    @Test
    fun `rejects blank and parses unclosed frontmatter leniently`() {
        assertNull(SkillMarkdown.parse("", "a"))
        assertNull(SkillMarkdown.parse("   \n", "a"))
        // Unclosed frontmatter → the whole file is treated as the body and the
        // name falls back (mobile installs must be forgiving, not throw).
        val lenient = SkillMarkdown.parse(
            "---\nname: broken\ndescription: never closed",
            "fallback",
        )
        assertEquals("fallback", lenient?.name)
        assertTrue(lenient?.instructions?.contains("name: broken") == true)
    }

    @Test
    fun `name normalization collapses separators`() {
        val raw = """
            ---
            description: d
            ---
            body
        """.trimIndent()
        assertEquals("my-cool-skill-v2", SkillMarkdown.parse(raw, "My Cool--Skill!  v2")?.name)
        assertEquals("my-cool-skill", SkillMarkdown.parse(raw, "My Cool--Skill!")?.name)
    }

    @Test
    fun `keeps body verbatim including fences`() {
        val body = """
            # Title

            ```json
            {"action":"write_file"}
            ```
        """.trimIndent()
        val raw = "---\nname: json-demo\ndescription: demo skill\n---\n$body"
        val skill = SkillMarkdown.parse(raw, fallbackName = "x")!!
        assertEquals(body, skill.instructions)
    }
}
