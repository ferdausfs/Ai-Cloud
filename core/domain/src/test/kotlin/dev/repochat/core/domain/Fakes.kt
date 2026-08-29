package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.CommitResult
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoFileTree
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.RepoSummary
import dev.repochat.core.model.TreeEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/* ------------------------------------------------------------------ */
/*  In-memory fakes used by unit tests (kept here so the domain layer  */
/*  can be exercised without Android/Room/network dependencies).       */
/* ------------------------------------------------------------------ */

class FakeOllamaService(
    private val responses: ArrayDeque<String> = ArrayDeque(),
    var failure: AppError? = null,
) : OllamaService {
    var lastMessages: List<OllamaMessage> = emptyList()
    var versionOverride: String = "0.9.0-test"

    override suspend fun version(): String {
        failure?.let { throw it }
        return versionOverride
    }

    override suspend fun chat(model: String, messages: List<OllamaMessage>): String {
        failure?.let { throw it }
        lastMessages = messages
        return if (responses.isNotEmpty()) responses.removeFirst()
        else """{"action":"reply","message":"done"}"""
    }
}

class FakeGithubService : GithubService {
    val files = mutableMapOf<String, GitFile>()
    var createdBranch: String? = null
    var committed: Triple<String, String, String?>? = null // path, branch, sha
    var failWith: AppError? = null

    override suspend fun listRepos(): List<RepoSummary> =
        listOf(RepoSummary(1, "demo", "acme/demo", false, "desc", "Kotlin", 0L, "main", ""))

    override suspend fun currentUserLogin(): String = "test-user"

    override suspend fun ensureWorkingBranch(owner: String, repo: String, sessionId: String, defaultBranch: String): String {
        val branch = "ai-chat/$sessionId"
        createdBranch = branch
        return branch
    }

    override suspend fun fileTree(owner: String, repo: String, branch: String): RepoFileTree =
        RepoFileTree(listOf(TreeEntry("src/Main.kt", "blob"), TreeEntry("README.md", "blob")), truncated = false)

    override suspend fun fileContent(owner: String, repo: String, path: String, branch: String): GitFile? {
        failWith?.let { throw it }
        return files[path]
    }

    override suspend fun commitFile(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        branch: String,
        baseSha: String?,
        commitMessage: String,
    ): CommitResult {
        committed = Triple(path, branch, baseSha)
        return CommitResult(path, "new-sha")
    }

    override suspend fun createPullRequest(
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PullRequestInfo = PullRequestInfo(1, "https://github.com/$owner/$repo/pull/1", title)
}

class FakeChatRepository(
    private val sessionState: MutableStateFlow<RepoSession?> = MutableStateFlow(null),
) : ChatRepository {
    val stored = mutableListOf<ChatMessage>()
    var nextId = 1L

    override fun session(repoKey: String): Flow<RepoSession?> = sessionState

    override suspend fun ensureSession(owner: String, repo: String, defaultBranch: String): RepoSession {
        val session = RepoSession(
            repoKey = "$owner/$repo", owner = owner, repo = repo, defaultBranch = defaultBranch,
            sessionId = "testsess1", workingBranch = null,
        )
        sessionState.value = session
        return session
    }

    override suspend fun updateWorkingBranch(repoKey: String, branch: String) {
        sessionState.value = sessionState.value?.copy(workingBranch = branch)
    }

    override fun messages(repoKey: String, sessionId: String): Flow<List<ChatMessage>> =
        sessionState.map { stored.filter { it.sessionId == sessionId } }

    override suspend fun recentMessages(repoKey: String, sessionId: String, limit: Int): List<ChatMessage> =
        stored.filter { it.sessionId == sessionId }.takeLast(limit)

    override suspend fun appendUserText(repoKey: String, sessionId: String, text: String): Long =
        append(ChatMessage(nextId++, repoKey, sessionId, ChatRole.USER, MessageKind.TEXT, text, null, null, null, null, MessageStatus.NONE, System.currentTimeMillis()))

    override suspend fun appendAiText(repoKey: String, sessionId: String, text: String): Long =
        append(ChatMessage(nextId++, repoKey, sessionId, ChatRole.AI, MessageKind.TEXT, text, null, null, null, null, MessageStatus.NONE, System.currentTimeMillis()))

    override suspend fun appendAiRead(repoKey: String, sessionId: String, path: String): Long =
        append(ChatMessage(nextId++, repoKey, sessionId, ChatRole.AI, MessageKind.READ_FILE, null, path, null, null, null, MessageStatus.NONE, System.currentTimeMillis()))

    override suspend fun appendAiWritePending(repoKey: String, sessionId: String, change: PendingChange): Long =
        append(ChatMessage(nextId++, repoKey, sessionId, ChatRole.AI, MessageKind.WRITE_FILE, null, change.path, null, null, change.commitMessage, MessageStatus.PENDING, System.currentTimeMillis()))

    override suspend fun markWrite(id: Long, status: MessageStatus, newSha: String?) {
        val idx = stored.indexOfFirst { it.id == id }
        if (idx >= 0) {
            stored[idx] = stored[idx].copy(status = status, base64Sha = newSha ?: stored[idx].base64Sha)
        }
    }

    override suspend fun clearMessages(repoKey: String, sessionId: String) {
        stored.removeAll { it.sessionId == sessionId }
    }

    private fun append(message: ChatMessage): Long {
        stored += message
        return message.id
    }
}

class FakeSettingsRepository(
    initial: AppSettings = AppSettings(modelName = "test-model"),
) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override fun cached(): AppSettings = state.value
    override suspend fun current(): AppSettings = state.value
    override suspend fun save(settings: AppSettings) {
        state.value = settings
    }
}
