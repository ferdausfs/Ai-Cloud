package dev.repochat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiActionParserTest {

    @Test
    fun `parses a valid reply action`() {
        val action = AiActionParser.parse("""{"action":"reply","message":"hello there"}""")
        assertTrue(action is AiAction.Reply)
        assertEquals("hello there", (action as AiAction.Reply).text)
    }

    @Test
    fun `parses a valid read_file action`() {
        val action = AiActionParser.parse("""{"action":"read_file","path":"src/main/Main.kt"}""")
        assertEquals(AiAction.ReadFile("src/main/Main.kt"), action)
    }

    @Test
    fun `parses a valid write_file action with commit message`() {
        val action = AiActionParser.parse(
            """{"action":"write_file","path":"a/b.txt","content":"hello","commit_message":"feat: add b"}"""
        )
        assertEquals(AiAction.WriteFile("a/b.txt", "hello", "feat: add b"), action)
    }

    @Test
    fun `parses json wrapped in markdown fences`() {
        val action = AiActionParser.parse(
            "```json\n{\"action\":\"reply\",\"message\":\"fenced\"}\n```"
        )
        assertTrue(action is AiAction.Reply)
        assertEquals("fenced", (action as AiAction.Reply).text)
    }

    @Test
    fun `falls back to a reply when the payload is not json`() {
        val action = AiActionParser.parse("I'm sorry, I can't do that.")
        assertTrue(action is AiAction.Reply)
        assertTrue((action as AiAction.Reply).text.contains("I'm sorry"))
    }

    @Test
    fun `falls back to a reply when action is unknown but message present`() {
        val action = AiActionParser.parse("""{"action":"dance","message":"twirl"}""")
        assertTrue(action is AiAction.Reply)
        assertEquals("twirl", (action as AiAction.Reply).text)
    }

    @Test
    fun `sanitizes paths that start with a slash`() {
        assertEquals("src/a.txt", AiActionParser.sanitizePath("/src/a.txt"))
    }

    @Test
    fun `rejects path traversal`() {
        assertNull(AiActionParser.sanitizePath("../../etc/passwd"))
        assertNull(AiActionParser.sanitizePath("a/../b"))
    }

    @Test
    fun `rejects git internals`() {
        assertNull(AiActionParser.sanitizePath(".git/config"))
    }

    @Test
    fun `parses create_pull_request action`() {
        val action = AiActionParser.parse(
            """{"action":"create_pull_request","title":"Fix bug","body":"Details here"}""",
        )
        assertEquals(AiAction.CreatePullRequest("Fix bug", "Details here"), action)
    }

    @Test
    fun `parses check_ci_status and ignores a branch the model supplied`() {
        val withBranch = AiActionParser.parse(
            """{"action":"check_ci_status","branch":"main"}""",
        )
        assertEquals(AiAction.CheckCiStatus, withBranch)
        val without = AiActionParser.parse("""{"action":"check_ci_status"}""")
        assertEquals(AiAction.CheckCiStatus, without)
    }

    @Test
    fun `tryParse rejects non-json so the loop can retry instead of showing raw text`() {
        assertNull(AiActionParser.tryParse("I'm sorry, I can't do that."))
    }

    @Test
    fun `tryParse rejects duplicated json objects`() {
        assertNull(
            AiActionParser.tryParse(
                """{"action":"check_ci_status","branch":"main"}{"action":"check_ci_status","branch":"main"}""",
            ),
        )
    }

    @Test
    fun `tryParse rejects unknown action names`() {
        assertNull(AiActionParser.tryParse("""{"action":"dance","message":"twirl"}"""))
    }
}
