package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiEditOrchestratorTest {

    private fun request() = TurnRequest(
        repoKey = "acme/demo",
        owner = "acme",
        repo = "demo",
        defaultBranch = "main",
        workingBranch = null,
        sessionId = "testsess1",
        userText = "fix the bug",
    )

    @Test
    fun `reply action stores and emits the message`() = runTest {
        val ollama = FakeLlmService(ArrayDeque(listOf("""{"action":"reply","message":"hello!"}""")))
        val github = FakeGithubService()
        val chat = FakeChatRepository()
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())

        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()

        assertTrue(events.any { it == TurnEvent.Reply("hello!") })
        assertEquals("ai-chat/testsess1", github.createdBranch)
        assertEquals(1, chat.stored.count { it.kind == dev.repochat.core.model.MessageKind.TEXT && it.role == dev.repochat.core.model.ChatRole.AI })
        assertTrue(ollama.lastMessages.first().content.contains("STRICT JSON ONLY"))
    }

    @Test
    fun `read then write round-trips through context and waits for approval`() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(
                listOf(
                    """{"action":"read_file","path":"src/Main.kt"}""",
                    """{"action":"write_file","path":"src/Main.kt","content":"fun main() {}","commit_message":"fix: main"}""",
                )
            )
        )
        val github = FakeGithubService().apply {
            files["src/Main.kt"] = GitFile("src/Main.kt", "fun main()", "sha-1", 10, isBinary = false)
        }
        val chat = FakeChatRepository()
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        // replay=1 so the approval emitted while handling ProposeWrite is
        // retained until the orchestrator suspends on approval.first()
        // (with replay=0 the emission would be dropped: no subscriber yet).
        val approval = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)

        val collected = mutableListOf<TurnEvent>()
        val job = launch {
            orchestrator.runTurn(request(), approval).collect { event ->
                collected += event
                if (event is TurnEvent.ProposeWrite) approval.emit(true)
            }
        }
        job.join()

        assertTrue(collected.any { it is TurnEvent.ReadingFile && it.path == "src/Main.kt" })
        assertTrue(collected.any { it is TurnEvent.ProposeWrite })
        assertTrue(collected.any { it is TurnEvent.WriteCommitted })
        assertEquals(Triple("src/Main.kt", "ai-chat/testsess1", "sha-1"), github.committed)
        // The model must have received the file content in context.
        assertTrue(ollama.lastMessages.any { it.content.contains("FILE CONTENT - src/Main.kt") })
        assertTrue(chat.stored.any { it.status == MessageStatus.APPROVED })
    }

    @Test
    fun `rejected write commits nothing`() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(listOf("""{"action":"write_file","path":"a.txt","content":"new","commit_message":"feat: a"}"""))
        )
        val github = FakeGithubService()
        val chat = FakeChatRepository()
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())
        val approval = MutableSharedFlow<Boolean>(replay = 1, extraBufferCapacity = 1)

        val collected = mutableListOf<TurnEvent>()
        val job = launch {
            orchestrator.runTurn(request(), approval).collect { event ->
                collected += event
                if (event is TurnEvent.ProposeWrite) approval.emit(false)
            }
        }
        job.join()

        assertTrue(collected.any { it is TurnEvent.WriteDeclined })
        assertNull(github.committed)
        assertTrue(chat.stored.any { it.status == MessageStatus.REJECTED })
    }

    @Test
    fun `missing model name surfaces a configuration error`() = runTest {
        val orchestrator = AiEditOrchestrator(
            FakeLlmService(), FakeGithubService(), FakeChatRepository(),
            FakeSettingsRepository(AppSettings(modelName = "  ")),
        )
        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()
        val error = events.filterIsInstance<TurnEvent.Error>().single().error
        assertTrue(error is AppError.Configuration)
    }

    @Test
    fun `rate limit errors are surfaced as typed events`() = runTest {
        val ollama = FakeLlmService(failure = AppError.RateLimited(AppError.Provider.OLLAMA, "rate limited"))
        val orchestrator = AiEditOrchestrator(
            ollama, FakeGithubService(), FakeChatRepository(), FakeSettingsRepository()
        )
        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()
        val error = events.filterIsInstance<TurnEvent.Error>().single().error
        assertTrue(error is AppError.RateLimited)
        assertEquals(AppError.Provider.OLLAMA, (error as AppError.RateLimited).provider)
    }

    @Test
    fun `text attachment is prepended to the user turn`() = runTest {
        val ollama = FakeLlmService(ArrayDeque(listOf("""{"action":"reply","message":"got it"}""")))
        val orchestrator = AiEditOrchestrator(
            ollama, FakeGithubService(), FakeChatRepository(), FakeSettingsRepository(),
        )
        val req = request().copy(
            userText = "please review",
            attachment = dev.repochat.core.model.ChatAttachment(
                displayName = "notes.txt",
                mimeType = "text/plain",
                textContent = "secret sauce",
            ),
        )
        orchestrator.runTurn(req, MutableSharedFlow()).toList()
        val userMsg = ollama.lastMessages.first { it.role == dev.repochat.core.model.OllamaRole.USER }
        assertTrue(userMsg.content.contains("ATTACHED FILE - notes.txt:"))
        assertTrue(userMsg.content.contains("secret sauce"))
        assertTrue(userMsg.content.contains("please review"))
        assertNull(userMsg.images)
    }

    @Test
    fun `image attachment omits bytes when model lacks vision`() = runTest {
        val ollama = FakeLlmService(ArrayDeque(listOf("""{"action":"reply","message":"no vision"}""")))
        val orchestrator = AiEditOrchestrator(
            ollama, FakeGithubService(), FakeChatRepository(),
            FakeSettingsRepository(AppSettings(modelName = "gpt-oss:120b-cloud")),
        )
        val req = request().copy(
            userText = "what is this?",
            attachment = dev.repochat.core.model.ChatAttachment(
                displayName = "shot.png",
                mimeType = "image/png",
                imageBase64 = "aGVsbG8=",
            ),
        )
        orchestrator.runTurn(req, MutableSharedFlow()).toList()
        val userMsg = ollama.lastMessages.first { it.role == dev.repochat.core.model.OllamaRole.USER }
        assertTrue(userMsg.content.contains("ATTACHED IMAGE - shot.png"))
        assertTrue(userMsg.content.contains("does not support vision"))
        assertNull(userMsg.images)
    }

    @Test
    fun `image attachment includes bytes for vision models`() = runTest {
        val ollama = FakeLlmService(ArrayDeque(listOf("""{"action":"reply","message":"i see it"}""")))
        val orchestrator = AiEditOrchestrator(
            ollama, FakeGithubService(), FakeChatRepository(),
            FakeSettingsRepository(AppSettings(modelName = "llava:13b")),
        )
        val req = request().copy(
            userText = "describe",
            attachment = dev.repochat.core.model.ChatAttachment(
                displayName = "shot.png",
                mimeType = "image/png",
                imageBase64 = "aGVsbG8=",
            ),
        )
        orchestrator.runTurn(req, MutableSharedFlow()).toList()
        val userMsg = ollama.lastMessages.first { it.role == dev.repochat.core.model.OllamaRole.USER }
        assertEquals(listOf("aGVsbG8="), userMsg.images)
        assertTrue(userMsg.content.contains("vision model"))
    }

    @Test
    fun `create_pull_request opens PR on working branch then replies`() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(
                listOf(
                    """{"action":"create_pull_request","title":"AI fixes","body":"please review"}""",
                    """{"action":"reply","message":"PR is up: https://github.com/acme/demo/pull/1"}""",
                ),
            ),
        )
        val github = FakeGithubService()
        val orchestrator = AiEditOrchestrator(ollama, github, FakeChatRepository(), FakeSettingsRepository())
        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()

        assertEquals(Triple("ai-chat/testsess1", "main", "AI fixes"), github.lastPrArgs)
        assertTrue(events.any { it is TurnEvent.PullRequestCreated })
        assertTrue(events.any { it is TurnEvent.Reply && it.text.contains("PR is up") })
        assertTrue(ollama.lastMessages.any { it.content.contains("PULL REQUEST CREATED") })
    }

    @Test
    fun `check_ci_status summarizes latest run`() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(
                listOf(
                    """{"action":"check_ci_status"}""",
                    """{"action":"reply","message":"Build is green."}""",
                ),
            ),
        )
        val github = FakeGithubService().apply {
            workflowRuns = listOf(
                dev.repochat.core.model.WorkflowRunInfo(
                    id = 9,
                    name = "Android CI",
                    status = "completed",
                    conclusion = "success",
                    htmlUrl = "https://github.com/acme/demo/actions/runs/9",
                ),
            )
        }
        val orchestrator = AiEditOrchestrator(ollama, github, FakeChatRepository(), FakeSettingsRepository())
        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()

        assertEquals("ai-chat/testsess1", github.lastCiBranch)
        val ci = events.filterIsInstance<TurnEvent.CiStatus>().single()
        assertEquals("success", ci.run?.conclusion)
        assertTrue(events.any { it is TurnEvent.Reply && it.text.contains("green") })
        assertTrue(ollama.lastMessages.any { it.content.contains("CI STATUS") })
    }

    @Test
    fun `general mode is plain chat without tools or branch`() = runTest {
        val ollama = FakeLlmService(ArrayDeque(listOf("Sure — use a sealed class for the states.")))
        val github = FakeGithubService()
        val chat = FakeChatRepository()
        val session = chat.createGeneralSession()
        val orchestrator = AiEditOrchestrator(ollama, github, chat, FakeSettingsRepository())

        val req = TurnRequest(
            repoKey = session.repoKey,
            owner = "",
            repo = "",
            defaultBranch = "",
            workingBranch = null,
            sessionId = session.sessionId,
            userText = "How do I model UI state in Compose?",
            mode = dev.repochat.core.model.ChatMode.GENERAL,
        )
        val events = orchestrator.runTurn(req, MutableSharedFlow()).toList()

        val reply = events.filterIsInstance<TurnEvent.Reply>().single()
        assertEquals("Sure — use a sealed class for the states.", reply.text)
        assertNull(github.createdBranch)
        assertNull(github.committed)
        assertTrue(ollama.lastMessages.first().content.contains("not attached to a repository"))
        assertTrue(ollama.lastMessages.none { it.content.contains("STRICT JSON ONLY") })
        assertEquals(1, chat.stored.count { it.role == dev.repochat.core.model.ChatRole.AI })
    }

    @Test
    fun `general mode unwraps JSON reply if model still uses tool schema`() = runTest {
        val ollama = FakeLlmService(
            ArrayDeque(listOf("""{"action":"reply","message":"plain answer"}""")),
        )
        val chat = FakeChatRepository()
        val session = chat.createGeneralSession()
        val orchestrator = AiEditOrchestrator(
            ollama, FakeGithubService(), chat, FakeSettingsRepository(),
        )
        val req = TurnRequest(
            repoKey = session.repoKey,
            owner = "",
            repo = "",
            defaultBranch = "",
            workingBranch = null,
            sessionId = session.sessionId,
            userText = "hi",
            mode = dev.repochat.core.model.ChatMode.GENERAL,
        )
        val events = orchestrator.runTurn(req, MutableSharedFlow()).toList()
        assertTrue(events.any { it == TurnEvent.Reply("plain answer") })
    }
}
