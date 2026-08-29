package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun `system prompt enforces the json contract`() {
        val prompt = PromptBuilder.system()
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("write_file"))
        assertTrue(prompt.contains("commit_message"))
        assertTrue(prompt.contains("STRICT JSON ONLY"))
    }

    @Test
    fun `user turn includes task, repo label and tree`() {
        val turn = PromptBuilder.userTurn(
            task = "fix the bug",
            owner = "acme",
            repo = "widgets",
            branch = "ai-chat/abc12345",
            treeText = "src/\na.txt",
            entryCount = 2,
        )
        assertTrue(turn.contains("fix the bug"))
        assertTrue(turn.contains("acme/widgets"))
        assertTrue(turn.contains("a.txt"))
    }

    @Test
    fun `cap keeps the system prompt and drops oldest first`() {
        val system = OllamaMessage(OllamaRole.SYSTEM, "s".repeat(100))
        val messages = listOf(
            system,
            OllamaMessage(OllamaRole.USER, "u".repeat(60)),
            OllamaMessage(OllamaRole.ASSISTANT, "a".repeat(60)),
            OllamaMessage(OllamaRole.USER, "u2".repeat(60)),
        )
        val capped = PromptBuilder.cap(messages, maxChars = 250)
        assertEquals(system, capped.first())
        assertTrue(capped.size < messages.size)
        // Newest user message must be retained.
        assertTrue(capped.last().content.startsWith("u2"))
    }

    @Test
    fun `attached file message matches file content style and truncates`() {
        val short = PromptBuilder.attachedFileMessage("notes.txt", "hello")
        assertTrue(short.startsWith("ATTACHED FILE - notes.txt:"))
        assertTrue(short.contains("hello"))

        val huge = "x".repeat(90_000)
        val truncated = PromptBuilder.attachedFileMessage("big.kt", huge)
        assertTrue(truncated.contains("truncated"))
        assertTrue(truncated.length < huge.length)
    }

    @Test
    fun `attached image message reflects vision support`() {
        val withVision = PromptBuilder.attachedImageMessage("shot.png", visionSupported = true)
        assertTrue(withVision.contains("vision"))
        val without = PromptBuilder.attachedImageMessage("shot.png", visionSupported = false)
        assertTrue(without.contains("does not support vision", ignoreCase = true))
    }

    @Test
    fun `modelSupportsVision detects known vision families`() {
        assertTrue(PromptBuilder.modelSupportsVision("llava:13b"))
        assertTrue(PromptBuilder.modelSupportsVision("qwen2-vl:7b"))
        assertTrue(!PromptBuilder.modelSupportsVision("gpt-oss:120b-cloud"))
        assertTrue(!PromptBuilder.modelSupportsVision(""))
    }
}
