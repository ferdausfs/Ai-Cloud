package dev.repochat.core.model

/**
 * How an LLM endpoint is reached. [OLLAMA] uses the bespoke Ollama Cloud API
 * (NDJSON). [OPENAI_COMPATIBLE] uses standard /v1/chat/completions.
 */
@kotlinx.serialization.Serializable
enum class ConnectionType { OLLAMA, OPENAI_COMPATIBLE, GITHUB, CLOUDFLARE, VERCEL, FIREBASE }

/**
 * One configured service endpoint (LLM provider or, later, other APIs).
 * Multiple OPENAI_COMPATIBLE rows let the user keep Groq + Cerebras + etc.
 *
 * Extra fields are type-specific:
 *   [ConnectionType.CLOUDFLARE] → [extraId] (Account ID), [apiKey] (API Token).
 *   [ConnectionType.VERCEL]     → [apiKey] (API Token); [baseUrl] is the Vercel API root.
 *   [ConnectionType.FIREBASE]   → [extraId] (Project ID), [apiKey] (Web API key or empty
 *                                 when a service account file is used), [serviceAccountJson]
 *                                 (file-picked service account JSON, stored separately).
 *
 * [modelName] is repurposed for per-type extras (e.g. Ollama model id); for
 * non-LLM connections it is usually blank.
 */
@kotlinx.serialization.Serializable
data class ServiceConnection(
    val id: String,
    val type: ConnectionType,
    /** User nickname, e.g. "Groq free tier". */
    val label: String,
    /** Base URL without trailing slash, e.g. https://api.groq.com/openai/v1
     * For Cloudflare/Vercel/Firebase this is the API root (or blank for Firebase). */
    val baseUrl: String = "",
    val apiKey: String = "",
    /** Default model for this connection (Ollama / OpenAI-compatible). For
     * Firebase this holds the Web API key when no service account is used. */
    val modelName: String = "",
    /** Cloudflare Account ID; Firebase Project ID; otherwise blank. */
    val extraId: String = "",
    /** Firebase service account JSON (file-picked); base64-encoded for storage.
     * Blank for all other connection types. */
    val serviceAccountJson: String = "",
) {
    val isFirebaseWithServiceAccount: Boolean
        get() = type == ConnectionType.FIREBASE && serviceAccountJson.isNotBlank()
}

/** Result of a single LLM completion, including which connection answered. */
data class LlmChatResult(
    val text: String,
    val connectionId: String,
    val providerLabel: String,
    /** Prior provider label when auto-fallback kicked in; null if first try won. */
    val fellBackFrom: String? = null,
)

/** User-configurable secrets/settings, persisted with EncryptedSharedPreferences. */
data class AppSettings(
    /** @deprecated Prefer [connections] of type OLLAMA — kept for migration. */
    val ollamaKey: String = "",
    /** @deprecated Prefer per-connection modelName. */
    val modelName: String = "",
    val githubPat: String = "",
    /** All configured connections (LLM + reserved types). */
    val connections: List<ServiceConnection> = emptyList(),
    /**
     * Ordered connection ids for auto-fallback. Only LLM connections are used
     * at chat time; missing ids are skipped.
     */
    val providerOrder: List<String> = emptyList(),
    /** Manual override for the next turns (null = follow [providerOrder]). */
    val activeProviderId: String? = null,
) {
    /** LLM connections only, in [providerOrder] then any extras. */
    fun llmConnectionsOrdered(): List<ServiceConnection> {
        val llms = connections.filter {
            it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE
        }
        if (llms.isEmpty()) return emptyList()
        val byId = llms.associateBy { it.id }
        val ordered = providerOrder.mapNotNull { byId[it] }
        val rest = llms.filter { it.id !in providerOrder.toSet() }
        return ordered + rest
    }

    fun connection(id: String?): ServiceConnection? =
        id?.let { id0 -> connections.firstOrNull { it.id == id0 } }

    fun activeLlmOrFirst(): ServiceConnection? {
        activeProviderId?.let { id -> connection(id) }?.let { c ->
            if (c.type == ConnectionType.OLLAMA || c.type == ConnectionType.OPENAI_COMPATIBLE) return c
        }
        return llmConnectionsOrdered().firstOrNull()
    }
}

data class RepoSummary(
    val id: Long,
    val name: String,
    val fullName: String,
    val isPrivate: Boolean,
    val description: String?,
    val language: String?,
    val updatedAtMillis: Long?,
    val defaultBranch: String,
    val htmlUrl: String,
    val stargazersCount: Int = 0,
) {
    val owner: String get() = fullName.substringBefore('/')
}

data class ActiveRepo(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val selectedAt: Long,
)

/** Whether a conversation is free-form or tied to a GitHub repository. */
enum class ChatMode { GENERAL, REPO }

/**
 * One AI conversation. Repo-mode sessions also name the working branch
 * (`ai-chat/{sessionId}`) that all AI commits go to — never main.
 * General-mode sessions have empty owner/repo and no GitHub tools.
 */
data class RepoSession(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val sessionId: String,
    val workingBranch: String?,
    val mode: ChatMode = ChatMode.REPO,
    /** Short list title (first user message, truncated). */
    val title: String? = null,
    val updatedAt: Long = 0L,
) {
    val isGeneral: Boolean get() = mode == ChatMode.GENERAL
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() }
            ?: if (isGeneral) "General chat" else "$owner/$repo"
}

/** Row for the Chats home list (Claude.ai-style). */
data class ConversationSummary(
    val session: RepoSession,
    val lastMessagePreview: String?,
    val lastMessageAt: Long,
)

data class GitFile(
    val path: String,
    val content: String,
    val sha: String,
    val sizeBytes: Long,
    val isBinary: Boolean,
)

data class TreeEntry(val path: String, val type: String)

data class RepoFileTree(val entries: List<TreeEntry>, val truncated: Boolean)

enum class ChatRole { USER, AI }

enum class MessageKind { TEXT, READ_FILE, WRITE_FILE }

enum class MessageStatus { NONE, PENDING, APPROVED, REJECTED }

data class ChatMessage(
    val id: Long,
    val repoKey: String,
    val sessionId: String,
    val role: ChatRole,
    val kind: MessageKind,
    val text: String?,
    val filePath: String?,
    val base64Content: String?,
    val base64Sha: String?,
    val commitMessage: String?,
    val status: MessageStatus,
    val createdAt: Long,
)

/** A proposed file change waiting for explicit user approval before commit. */
data class PendingChange(
    val path: String,
    val oldContent: String,
    val newContent: String,
    val baseSha: String?,
    val branch: String,
    val commitMessage: String,
    val isNew: Boolean,
    val additions: Int,
    val removals: Int,
)

data class CommitResult(val path: String, val newSha: String)

data class PullRequestInfo(val number: Long, val htmlUrl: String, val title: String)

/** One GitHub Actions workflow run, used for CI status checks. */
data class WorkflowRunInfo(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String?,
    val updatedAtMillis: Long? = null,
) {
    /** Short human-readable label for chips / chat replies. */
    fun summarize(): String {
        val label = name.ifBlank { "CI" }
        return when {
            status == "queued" -> "$label: queued"
            status == "in_progress" -> "$label: in progress"
            conclusion == "success" -> "$label: success"
            conclusion == "failure" -> "$label: failed"
            conclusion == "cancelled" -> "$label: cancelled"
            conclusion == "skipped" -> "$label: skipped"
            conclusion != null -> "$label: $conclusion"
            else -> "$label: $status"
        }
    }

    /** Compact chip text. */
    fun chipLabel(): String = when {
        status == "queued" -> "CI queued"
        status == "in_progress" -> "CI running"
        conclusion == "success" -> "CI passed"
        conclusion == "failure" -> "CI failed"
        conclusion == "cancelled" -> "CI cancelled"
        conclusion != null -> "CI $conclusion"
        else -> "CI $status"
    }
}

/** One job inside a workflow run (with optional step summaries). */
data class WorkflowJobInfo(
    val id: Long,
    val name: String,
    val conclusion: String?,
    val status: String = "",
    val steps: List<WorkflowStepInfo> = emptyList(),
)

data class WorkflowStepInfo(
    val name: String,
    val conclusion: String?,
    val number: Int,
)

data class ConnectionResult(val ok: Boolean, val detail: String)

/* ---- Multi-service connection info models (Cloudflare/Vercel/Firebase) ---- */

data class CloudflareAccountInfo(
    val id: String,
    val email: String?,
    val name: String?,
)

data class CloudflareZoneInfo(
    val id: String,
    val name: String,
    val status: String,
    val planName: String?,
)

data class CloudflareWorkerScriptInfo(
    val id: String,
    val name: String,
    val etag: String?,
)

data class VercelProjectInfo(
    val id: String,
    val name: String,
    val framework: String?,
)

data class VercelDeploymentInfo(
    val id: String,
    val url: String,
    val status: String,
    val state: String,
    val projectId: String?,
    val projectName: String?,
)

data class FirebaseProjectInfo(
    val name: String,
    val projectId: String,
    val projectNumber: String?,
)

data class FirebaseServiceInfo(
    val name: String,
    val state: String,
)

/**
 * A file the user attached to a chat turn before sending. Text attachments
 * contribute their content to the model prompt; image attachments contribute
 * either vision bytes (when the model supports it) or a plain-text note.
 */
data class ChatAttachment(
    val displayName: String,
    val mimeType: String?,
    /** UTF-8 text for text/code files; null for binary/image attachments. */
    val textContent: String? = null,
    /** Raw base64 (no data-URI prefix) for image attachments; null otherwise. */
    val imageBase64: String? = null,
) {
    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true || imageBase64 != null
}

data class TurnRequest(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val workingBranch: String?,
    val sessionId: String,
    val userText: String,
    val attachment: ChatAttachment? = null,
    val mode: ChatMode = ChatMode.REPO,
    /**
     * When true, [dev.repochat.core.domain.AutoFixLoop] runs after the first
     * successful commit: poll CI, fetch failure logs, and re-prompt the model
     * until green or [autoFixMaxAttempts] is exhausted.
     */
    val autoFixUntilCiGreen: Boolean = false,
    val autoFixMaxAttempts: Int = 5,
    /** Force a specific LLM connection for this turn (manual chip/picker). */
    val preferredConnectionId: String? = null,
) {
    val isGeneral: Boolean get() = mode == ChatMode.GENERAL
}

/** Progress events emitted while an AI turn runs. */
sealed interface TurnEvent {
    data class Working(val step: String) : TurnEvent
    data class TreeReady(val truncated: Boolean) : TurnEvent
    data class ReadingFile(val path: String) : TurnEvent
    data class Reply(val text: String) : TurnEvent
    /** Informational note when auto-fallback switched providers mid-turn. */
    data class ProviderNote(val text: String) : TurnEvent
    data class ProposeWrite(val messageId: Long, val change: PendingChange) : TurnEvent
    data class WriteCommitted(val messageId: Long, val change: PendingChange) : TurnEvent
    data class WriteDeclined(val messageId: Long, val change: PendingChange) : TurnEvent
    /** Model created a pull request mid-conversation. */
    data class PullRequestCreated(val info: PullRequestInfo) : TurnEvent
    /** Latest Actions run for the working branch (after check_ci_status). */
    data class CiStatus(val run: WorkflowRunInfo?) : TurnEvent
    /** Auto-fix loop progress (opt-in "fix until CI green"). */
    data class AutoFixProgress(val event: AutoFixEvent) : TurnEvent
    data class Error(val error: AppError) : TurnEvent
}

/**
 * Progress of the autonomous fix-until-CI-green loop. Surfaced as chat
 * bubbles and notification text so the user can leave the app mid-loop.
 */
sealed interface AutoFixEvent {
    data class AttemptStarted(val attempt: Int, val maxAttempts: Int) : AutoFixEvent
    data class Committed(val attempt: Int, val summary: String) : AutoFixEvent
    data class CiPending(val attempt: Int, val run: WorkflowRunInfo?) : AutoFixEvent
    data class CiPassed(val attempt: Int, val run: WorkflowRunInfo) : AutoFixEvent
    data class CiFailed(val attempt: Int, val run: WorkflowRunInfo?, val logExcerpt: String) : AutoFixEvent
    data class GaveUp(val attemptsMade: Int, val history: List<String>, val lastLogExcerpt: String?) : AutoFixEvent
    data class Error(val error: AppError) : AutoFixEvent
}
