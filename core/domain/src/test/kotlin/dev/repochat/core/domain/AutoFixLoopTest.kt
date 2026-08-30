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
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFixLoopTest {

    private fun request(task: String = "make it build") = TurnRequest(
        repoKey = "acme/demo",
        owner = "acme",
        repo = "demo",
        defaultBranch = "main",
        workingBranch = null,
        sessionId = "testsess1",
        userText = task,
        autoFixUntilCiGreen = true,
        autoFixMaxAttempts = 3,
    )

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun writeAction(path: String, content: String, msg: String) =
        """{"action":"write_file","path":"$path","content":${q(content)},"commit_message":"$msg"}"""

    private fun replyAction(text: String) =
        """{"action":"reply","message":${q(text)}}"""

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
    fun loop_passesOnFirstGreenCi() = runTest {
        val ollama = FakeOllamaService(
            ArrayDeque(
                listOf(
                    writeAction("src/Main.kt", "fun main()={}", "fix: compile"),
                ),
            ),
        )
        val github = FakeGithubService().apply {
            files["src/Main.kt"] = GitFile("src/Main.kt", "old", "sha1", 3, false)
            // Baseline empty, then a completed success for the new commit.
            workflowRunSequence += emptyList() // baseline peek
            workflowRunSequence += listOf(
                WorkflowRunInfo(10, "Android CI", "completed", "success", "https://ci/10"),
            )
        }
        val chat = FakeChatRepository()
        chat.ensureSession("acme", "demo", "main")
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val loop = testLoop(orchestrator, github, chat)

        val events = loop.run(request(), maxAttempts = 3).toList()
        val progress = events.filterIsInstance<TurnEvent.AutoFixProgress>().map { it.event }
        assertTrue(progress.any { it is AutoFixEvent.AttemptStarted })
        assertTrue(progress.any { it is AutoFixEvent.Committed })
        assertTrue(progress.any { it is AutoFixEvent.CiPassed })
        assertTrue(events.any { it is TurnEvent.Reply && (it as TurnEvent.Reply).text.contains("green") })
        assertEquals("ai-chat/testsess1", github.committed?.second)
    }

    @Test
    fun loop_fetchesRealLog_onFailure_thenPasses() = runTest {
        val ollama = FakeOllamaService(
            ArrayDeque(
                listOf(
                    // Attempt 1: bad write
                    writeAction("src/Main.kt", "fun broken() = NOPE", "fix: broken"),
                    // Attempt 2: real fix after seeing the log
                    writeAction("src/Main.kt", "fun main() = Unit", "fix: real fix"),
                ),
            ),
        )
        val github = FakeGithubService().apply {
            files["src/Main.kt"] = GitFile("src/Main.kt", "old", "sha1", 3, false)
            workflowRunSequence += emptyList() // baseline
            // After attempt 1 commit: failure
            workflowRunSequence += listOf(
                WorkflowRunInfo(21, "Android CI", "completed", "failure", "https://ci/21"),
            )
            // After attempt 2 commit: success
            workflowRunSequence += listOf(
                WorkflowRunInfo(22, "Android CI", "completed", "success", "https://ci/22"),
            )
            jobsByRunId[21] = listOf(
                WorkflowJobInfo(
                    id = 210,
                    name = "Build & unit tests",
                    conclusion = "failure",
                    steps = listOf(
                        WorkflowStepInfo("Assemble debug APK", "failure", 5),
                    ),
                ),
            )
            jobLogs[210] = "e: file://src/Main.kt:1:1 Unresolved reference: NOPE\nFAILED"
        }
        val chat = FakeChatRepository()
        chat.ensureSession("acme", "demo", "main")
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val loop = testLoop(orchestrator, github, chat)

        val events = loop.run(request("fix Main.kt"), maxAttempts = 3).toList()
        val progress = events.filterIsInstance<TurnEvent.AutoFixProgress>().map { it.event }

        val failed = progress.filterIsInstance<AutoFixEvent.CiFailed>()
        assertEquals(1, failed.size)
        assertTrue(
            "expected real log excerpt, got: ${failed[0].logExcerpt}",
            failed[0].logExcerpt.contains("Unresolved reference: NOPE"),
        )
        assertEquals(210L, github.lastLogJobId)

        assertTrue(progress.any { it is AutoFixEvent.CiPassed })
        // Second write used the fix prompt containing the log.
        assertTrue(
            ollama.lastMessages.any { it.content.contains("Unresolved reference: NOPE") },
        )
    }

    @Test
    fun loop_givesUpWithHonestSummary() = runTest {
        val ollama = FakeOllamaService(
            ArrayDeque(
                listOf(
                    writeAction("a.kt", "x", "try 1"),
                    writeAction("a.kt", "y", "try 2"),
                ),
            ),
        )
        val github = FakeGithubService().apply {
            files["a.kt"] = GitFile("a.kt", "old", "sha", 1, false)
            workflowRunSequence += emptyList()
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

        val events = loop.run(request("impossible"), maxAttempts = 2).toList()
        val gaveUp = events.filterIsInstance<TurnEvent.AutoFixProgress>()
            .map { it.event }
            .filterIsInstance<AutoFixEvent.GaveUp>()
        assertEquals(1, gaveUp.size)
        assertEquals(2, gaveUp[0].attemptsMade)
        val reply = events.filterIsInstance<TurnEvent.Reply>().last()
        assertTrue(reply.text.contains("couldn't get CI green", ignoreCase = true))
        assertTrue(reply.text.contains("Want me to keep trying"))
        // Never claim success.
        assertTrue(events.none { it is TurnEvent.AutoFixProgress && it.event is AutoFixEvent.CiPassed })
    }

    private fun testLoop(
        orchestrator: AiEditOrchestrator,
        github: FakeGithubService,
        chat: FakeChatRepository,
    ) = AutoFixLoop(orchestrator, github, chat).apply {
        // Wall-clock budget stays large; poll cap + 0 delays keep tests fast
        // under runTest virtual time (System.currentTimeMillis does not advance).
        ciWaitBudgetMs = 60_000
        ciPollInitialMs = 0
        ciPollMaxMs = 0
        logRetryDelayMs = 0
        ciMaxPolls = 6
    }
}
