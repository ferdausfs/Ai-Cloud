package dev.repochat.core.domain

import dev.repochat.core.model.AutoFixEvent
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
import dev.repochat.core.model.WorkflowStepInfo
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFixLoopTest {

    private fun request(task: String = "make it build", max: Int = 3) = TurnRequest(
        repoKey = "acme/demo",
        owner = "acme",
        repo = "demo",
        defaultBranch = "main",
        workingBranch = null,
        sessionId = "testsess1",
        userText = task,
        autoFixUntilCiGreen = true,
        autoFixMaxAttempts = max,
    )

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeAction(path: String, content: String, msg: String) =
        """{"action":"write_file","path":"$path","content":${q(content)},"commit_message":"$msg"}"""

    private fun testLoop(
        orchestrator: AiEditOrchestrator,
        github: FakeGithubService,
        chat: FakeChatRepository,
    ) = AutoFixLoop(orchestrator, github, chat).apply {
        ciWaitBudgetMs = 60_000
        ciPollInitialMs = 0
        ciPollMaxMs = 0
        logRetryDelayMs = 0
        ciMaxPolls = 8
    }

    @Test
    fun truncateTail_keepsLastChars() {
        val long = "a".repeat(10_000)
        val out = AutoFixLoop.truncateTail(long, 100)
        assertTrue(out.endsWith("a".repeat(100)))
        assertTrue(out.startsWith("…(log truncated"))
    }

    @Test
    fun buildFixPrompt_includesLogAndTask() {
        val prompt = AutoFixLoop.buildFixPrompt(
            originalTask = "add dark mode",
            history = listOf("Attempt 1: CI failed"),
            logExcerpt = "e: Unresolved reference: Foo",
        )
        assertTrue(prompt.contains("add dark mode"))
        assertTrue(prompt.contains("Unresolved reference: Foo"))
        assertTrue(prompt.contains("Attempt 1"))
    }

    @Test
    fun buildGaveUpMessage_isHonestAndAsksNextStep() {
        val msg = AutoFixLoop.buildGaveUpMessage(
            originalTask = "ship it",
            attemptsMade = 5,
            history = listOf("Attempt 1: CI failed — boom"),
            lastLog = "e: boom",
        )
        assertTrue(msg.contains("couldn't get CI green", ignoreCase = true))
        assertTrue(msg.contains("Want me to keep trying"))
        assertFalse(msg.contains("CI is green"))
    }

    @Test
    fun loop_passesOnFirstGreenCi() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(listOf(writeAction("src/Main.kt", "fun main()={}", "fix: compile"))),
        )
        val github = FakeGithubService().apply {
            files["src/Main.kt"] = GitFile("src/Main.kt", "old", "sha1", 3, false)
            // No baseline (workingBranch null). First poll after commit is green.
            workflowRunSequence += listOf(
                WorkflowRunInfo(10, "Android CI", "completed", "success", "https://ci/10"),
            )
        }
        val chat = FakeChatRepository()
        chat.ensureSession("acme", "demo", "main")
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val loop = testLoop(orchestrator, github, chat)

        val events = loop.run(request(), maxAttempts = 3).toList()
        val progress = events.mapNotNull { (it as? TurnEvent.AutoFixProgress)?.event }

        assertTrue("missing AttemptStarted: $progress", progress.any { it is AutoFixEvent.AttemptStarted })
        assertTrue("missing Committed: $progress", progress.any { it is AutoFixEvent.Committed })
        assertTrue("missing CiPassed: $progress", progress.any { it is AutoFixEvent.CiPassed })
        val replies = events.mapNotNull { (it as? TurnEvent.Reply)?.text }
        assertTrue("no green reply in $replies", replies.any { it.contains("green", ignoreCase = true) })
        assertEquals("ai-chat/testsess1", github.committed?.second)
    }

    @Test
    fun loop_fetchesRealLog_onFailure_thenPasses() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(
                listOf(
                    writeAction("src/Main.kt", "fun broken() = NOPE", "fix: broken"),
                    writeAction("src/Main.kt", "fun main() = Unit", "fix: real fix"),
                ),
            ),
        )
        val github = FakeGithubService().apply {
            files["src/Main.kt"] = GitFile("src/Main.kt", "old", "sha1", 3, false)
            workflowRunSequence += listOf(
                WorkflowRunInfo(21, "Android CI", "completed", "failure", "https://ci/21"),
            )
            workflowRunSequence += listOf(
                WorkflowRunInfo(22, "Android CI", "completed", "success", "https://ci/22"),
            )
            jobsByRunId[21] = listOf(
                WorkflowJobInfo(
                    id = 210,
                    name = "Build & unit tests",
                    conclusion = "failure",
                    steps = listOf(WorkflowStepInfo("Assemble debug APK", "failure", 5)),
                ),
            )
            jobLogs[210] = "e: file://src/Main.kt:1:1 Unresolved reference: NOPE\nFAILED"
        }
        val chat = FakeChatRepository()
        chat.ensureSession("acme", "demo", "main")
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val loop = testLoop(orchestrator, github, chat)

        val events = loop.run(request("fix Main.kt"), maxAttempts = 3).toList()
        val progress = events.mapNotNull { (it as? TurnEvent.AutoFixProgress)?.event }

        val failed = progress.filterIsInstance<AutoFixEvent.CiFailed>()
        assertEquals("expected one CiFailed, got $progress", 1, failed.size)
        assertTrue(
            "expected real log excerpt, got: ${failed[0].logExcerpt}",
            failed[0].logExcerpt.contains("Unresolved reference: NOPE"),
        )
        assertEquals(210L, github.lastLogJobId)
        assertTrue("missing CiPassed: $progress", progress.any { it is AutoFixEvent.CiPassed })
    }

    @Test
    fun loop_givesUpWithHonestSummary() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(
                listOf(
                    writeAction("a.kt", "x", "try 1"),
                    writeAction("a.kt", "y", "try 2"),
                ),
            ),
        )
        val github = FakeGithubService().apply {
            files["a.kt"] = GitFile("a.kt", "old", "sha", 1, false)
            workflowRunSequence += listOf(
                WorkflowRunInfo(1, "CI", "completed", "failure", null),
            )
            workflowRunSequence += listOf(
                WorkflowRunInfo(2, "CI", "completed", "failure", null),
            )
            jobsByRunId[1] = listOf(WorkflowJobInfo(11, "build", "failure"))
            jobsByRunId[2] = listOf(WorkflowJobInfo(12, "build", "failure"))
            jobLogs[11] = "error: still broken A"
            jobLogs[12] = "error: still broken B"
        }
        val chat = FakeChatRepository()
        chat.ensureSession("acme", "demo", "main")
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val loop = testLoop(orchestrator, github, chat)

        val events = loop.run(request("impossible", max = 2), maxAttempts = 2).toList()
        val progress = events.mapNotNull { (it as? TurnEvent.AutoFixProgress)?.event }
        val gaveUp = progress.filterIsInstance<AutoFixEvent.GaveUp>()
        assertEquals("expected GaveUp in $progress", 1, gaveUp.size)
        assertEquals(2, gaveUp[0].attemptsMade)
        val reply = events.mapNotNull { (it as? TurnEvent.Reply)?.text }.last()
        assertTrue(reply.contains("couldn't get CI green", ignoreCase = true))
        assertTrue(reply.contains("Want me to keep trying"))
        assertTrue(
            "must not claim success: $progress",
            progress.none { it is AutoFixEvent.CiPassed },
        )
    }
}
