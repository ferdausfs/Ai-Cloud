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
        val ollama = FakeOllamaService(ArrayDeque(listOf("""{"action":"reply","message":"hello!"}""")))
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
        val ollama = FakeOllamaService(
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
        val ollama = FakeOllamaService(
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
            FakeOllamaService(), FakeGithubService(), FakeChatRepository(),
            FakeSettingsRepository(AppSettings(modelName = "  ")),
        )
        val events = orchestrator.runTurn(request(), MutableSharedFlow()).toList()
        val error = events.filterIsInstance<TurnEvent.Error>().single().error
        assertTrue(error is AppError.Configuration)
    }

    @Test
    fun `rate limit errors are surfaced as typed events`() = runTest {
        val ollama = FakeOllamaService(failure = AppError.RateLimited(AppError.Provider.OLLAMA, "rate limited"))
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
        val ollama = FakeOllamaService(ArrayDeque(listOf("""{"action":"reply","message":"got it"}""")))
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
        val ollama = FakeOllamaService(ArrayDeque(listOf("""{"action":"reply","message":"no vision"}""")))
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
        val ollama = FakeOllamaService(ArrayDeque(listOf("""{"action":"reply","message":"i see it"}""")))
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
}
