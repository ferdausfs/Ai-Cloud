package dev.repochat.core.model

/** User-configurable secrets/settings, persisted in EncryptedSharedPreferences. */
data class AppSettings(
    val ollamaKey: String = "",
    val modelName: String = "",
    val githubPat: String = "",
)

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

/**
 * One AI editing session per repository. The session id names the working
 * branch (`ai-chat/{sessionId}`) that all AI commits go to — never main.
 */
data class RepoSession(
    val repoKey: String,
    val owner: String,
    val repo: String,
    val defaultBranch: String,
    val sessionId: String,
    val workingBranch: String?,
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
    /**
     * When true, [dev.repochat.core.domain.AutoFixLoop] runs after the first
     * successful commit: poll CI, fetch failure logs, and re-prompt the model
     * until green or [autoFixMaxAttempts] is exhausted.
     */
    val autoFixUntilCiGreen: Boolean = false,
    val autoFixMaxAttempts: Int = 5,
)

/** Progress events emitted while an AI turn runs. */
sealed interface TurnEvent {
    data class Working(val step: String) : TurnEvent
    data class TreeReady(val truncated: Boolean) : TurnEvent
    data class ReadingFile(val path: String) : TurnEvent
    data class Reply(val text: String) : TurnEvent
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
