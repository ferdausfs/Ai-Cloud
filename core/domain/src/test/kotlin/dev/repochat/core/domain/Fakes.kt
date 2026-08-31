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
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
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

    override suspend fun listModels(apiKeyOverride: String?): List<String> = emptyList()

    override suspend fun chat(
        model: String,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        apiKeyOverride: String?,
    ): String {
        failure?.let { throw it }
        lastMessages = messages
        return if (responses.isNotEmpty()) responses.removeFirst()
        else """{"action":"reply","message":"done"}"""
    }
}

/** Test double for [LlmService] — wraps a response queue like FakeOllamaService. */
class FakeLlmService(
    private val responses: ArrayDeque<String> = ArrayDeque(),
    var failure: AppError? = null,
    var label: String = "TestLLM",
) : LlmService {
    var lastMessages: List<OllamaMessage> = emptyList()
    var lastJsonMode: Boolean? = null
    var callCount: Int = 0

    override suspend fun chat(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String?,
    ): dev.repochat.core.model.LlmChatResult {
        callCount++
        failure?.let { throw it }
        lastMessages = messages
        lastJsonMode = jsonMode
        val text = if (responses.isNotEmpty()) responses.removeFirst()
        else """{"action":"reply","message":"done"}"""
        return dev.repochat.core.model.LlmChatResult(
            text = text,
            connectionId = "test",
            providerLabel = label,
        )
    }

    override suspend fun test(connection: dev.repochat.core.model.ServiceConnection): String = "ok"

    override suspend fun listModels(
        connection: dev.repochat.core.model.ServiceConnection,
    ): List<String> = emptyList()
}

class FakeGithubService : GithubService {
    val files = mutableMapOf<String, GitFile>()
    var createdBranch: String? = null
    var committed: Triple<String, String, String?>? = null // path, branch, sha
    var failWith: AppError? = null
    var lastPr: PullRequestInfo? = null
    var lastPrArgs: Triple<String, String, String>? = null // head, base, title
    var workflowRuns: List<WorkflowRunInfo> = emptyList()
    var lastCiBranch: String? = null

    override suspend fun listRepos(): List<RepoSummary> =
        listOf(
            RepoSummary(
                id = 1,
                name = "demo",
                fullName = "acme/demo",
                isPrivate = false,
                description = "desc",
                language = "Kotlin",
                updatedAtMillis = 0L,
                defaultBranch = "main",
                htmlUrl = "",
                stargazersCount = 0,
            ),
        )

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
    ): PullRequestInfo {
        failWith?.let { throw it }
        lastPrArgs = Triple(head, base, title)
        val info = PullRequestInfo(1, "https://github.com/$owner/$repo/pull/1", title)
        lastPr = info
        return info
    }

    override suspend fun listWorkflowRuns(
        owner: String,
        repo: String,
        branch: String,
        perPage: Int,
    ): List<WorkflowRunInfo> {
        failWith?.let { throw it }
        lastCiBranch = branch
        // Allow tests to advance a scripted sequence of run lists per poll.
        if (workflowRunSequence.isNotEmpty()) {
            val next = workflowRunSequence.removeFirst()
            workflowRuns = next
        }
        return workflowRuns.take(perPage)
    }

    override suspend fun listJobsForRun(
        owner: String,
        repo: String,
        runId: Long,
    ): List<WorkflowJobInfo> {
        failWith?.let { throw it }
        lastJobsRunId = runId
        return jobsByRunId[runId].orEmpty()
    }

    override suspend fun getJobLogs(
        owner: String,
        repo: String,
        jobId: Long,
    ): String {
        failWith?.let { throw it }
        lastLogJobId = jobId
        return jobLogs[jobId].orEmpty()
    }

    var lastJobsRunId: Long? = null
    var lastLogJobId: Long? = null
    val jobsByRunId = mutableMapOf<Long, List<WorkflowJobInfo>>()
    val jobLogs = mutableMapOf<Long, String>()
    /** FIFO of run lists returned by successive [listWorkflowRuns] polls. */
    val workflowRunSequence = ArrayDeque<List<WorkflowRunInfo>>()
}

class FakeChatRepository(
    private val sessionState: MutableStateFlow<RepoSession?> = MutableStateFlow(null),
) : ChatRepository {
    val stored = mutableListOf<ChatMessage>()
    val allSessions = mutableListOf<RepoSession>()
    var nextId = 1L

    override fun session(repoKey: String): Flow<RepoSession?> = sessionState.map { s ->
        s?.takeIf { it.repoKey == repoKey } ?: allSessions.firstOrNull { it.repoKey == repoKey }
    }

    override suspend fun getSession(repoKey: String): RepoSession? =
        allSessions.firstOrNull { it.repoKey == repoKey }
            ?: sessionState.value?.takeIf { it.repoKey == repoKey }

    override fun conversations(): Flow<List<dev.repochat.core.model.ConversationSummary>> =
        sessionState.map {
            allSessions.map { s ->
                val last = stored.filter { it.repoKey == s.repoKey }.maxByOrNull { it.createdAt }
                dev.repochat.core.model.ConversationSummary(
                    session = s,
                    lastMessagePreview = last?.text,
                    lastMessageAt = last?.createdAt ?: s.updatedAt,
                )
            }.sortedByDescending { it.lastMessageAt }
        }

    override suspend fun ensureSession(owner: String, repo: String, defaultBranch: String): RepoSession {
        val key = "$owner/$repo"
        allSessions.firstOrNull { it.repoKey == key }?.let {
            sessionState.value = it
            return it
        }
        val session = RepoSession(
            repoKey = key, owner = owner, repo = repo, defaultBranch = defaultBranch,
            sessionId = "testsess1", workingBranch = null,
            mode = dev.repochat.core.model.ChatMode.REPO,
            updatedAt = System.currentTimeMillis(),
        )
        allSessions += session
        sessionState.value = session
        return session
    }

    override suspend fun createGeneralSession(): RepoSession {
        val id = "gen${nextId++}"
        val session = RepoSession(
            repoKey = "general/$id",
            owner = "",
            repo = "",
            defaultBranch = "",
            sessionId = id,
            workingBranch = null,
            mode = dev.repochat.core.model.ChatMode.GENERAL,
            updatedAt = System.currentTimeMillis(),
        )
        allSessions += session
        sessionState.value = session
        return session
    }

    override suspend fun updateWorkingBranch(repoKey: String, branch: String) {
        val idx = allSessions.indexOfFirst { it.repoKey == repoKey }
        if (idx >= 0) {
            allSessions[idx] = allSessions[idx].copy(workingBranch = branch)
            sessionState.value = allSessions[idx]
        }
    }

    override suspend fun deleteConversation(repoKey: String) {
        allSessions.removeAll { it.repoKey == repoKey }
        stored.removeAll { it.repoKey == repoKey }
        if (sessionState.value?.repoKey == repoKey) sessionState.value = null
    }

    override fun messages(repoKey: String, sessionId: String): Flow<List<ChatMessage>> =
        sessionState.map { stored.filter { it.repoKey == repoKey && it.sessionId == sessionId } }

    override suspend fun recentMessages(repoKey: String, sessionId: String, limit: Int): List<ChatMessage> =
        stored.filter { it.repoKey == repoKey && it.sessionId == sessionId }.takeLast(limit)

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
        stored.removeAll { it.repoKey == repoKey && it.sessionId == sessionId }
    }

    override suspend fun hasApprovedWrite(repoKey: String, sessionId: String): Boolean =
        stored.any {
            it.repoKey == repoKey && it.sessionId == sessionId &&
                it.kind == MessageKind.WRITE_FILE && it.status == MessageStatus.APPROVED
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
