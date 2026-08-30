package dev.repochat.core.domain

import dev.repochat.core.model.AiAction
import dev.repochat.core.model.AiActionParser
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ChatRole
import dev.repochat.core.model.FileTreeFormatter
import dev.repochat.core.model.LineDiffer
import dev.repochat.core.model.MessageKind
import dev.repochat.core.model.MessageStatus
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.OllamaRole
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PromptBuilder
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Implements the AI editing loop:
 *
 *  1. Ensure the per-session working branch exists (never commit to main).
 *  2. Fetch + format the repository file tree.
 *  3. Ask the model for a strict JSON action; on read_file, feed the file
 *     contents back into the context and loop.
 *  4. On write_file, suspend until the user approves or rejects via
 *     [approval]; only an approval triggers the GitHub commit.
 *  5. On reply, store and emit the message.
 */
@Singleton
class AiEditOrchestrator @Inject constructor(
    private val ollama: OllamaService,
    private val github: GithubService,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
) : AiTurnRunner {

    override fun runTurn(request: TurnRequest, approval: Flow<Boolean>): Flow<TurnEvent> = flow {
        val model = settings.current().modelName.trim()
        if (model.isEmpty()) {
            throw AppError.Configuration(
                "No model configured yet. Add a model name (e.g. gpt-oss:120b-cloud) in Settings."
            )
        }

        if (request.isGeneral) {
            runGeneralTurn(request, model)
            return@flow
        }

        emit(TurnEvent.Working("Checking working branch"))
        val branch = github.ensureWorkingBranch(
            owner = request.owner,
            repo = request.repo,
            sessionId = request.sessionId,
            defaultBranch = request.defaultBranch,
        )
        chat.updateWorkingBranch(request.repoKey, branch)

        emit(TurnEvent.Working("Fetching repository tree"))
        val tree = github.fileTree(request.owner, request.repo, branch)
        emit(TurnEvent.TreeReady(tree.truncated))

        val history = chat.recentMessages(request.repoKey, request.sessionId, limit = 8)
            .filter { it.kind == MessageKind.TEXT && !it.text.isNullOrBlank() }
            // The most recent message is the user message that triggered this
            // turn and is passed separately as the TASK — don't duplicate it.
            .dropLast(1)
            .map {
                OllamaMessage(
                    role = if (it.role == ChatRole.USER) OllamaRole.USER else OllamaRole.ASSISTANT,
                    content = it.text.orEmpty(),
                )
            }

        val visionSupported = PromptBuilder.modelSupportsVision(model)
        val attachment = request.attachment
        val attachedText = attachment?.textContent
        val attachedImageB64 = attachment?.imageBase64
        val taskText = buildString {
            when {
                attachment == null -> Unit
                !attachedText.isNullOrEmpty() -> {
                    append(PromptBuilder.attachedFileMessage(attachment.displayName, attachedText))
                    append("\n\n")
                }
                attachment.isImage -> {
                    append(PromptBuilder.attachedImageMessage(attachment.displayName, visionSupported))
                    append("\n\n")
                }
            }
            append(request.userText)
        }
        val userImages = attachedImageB64
            ?.takeIf { visionSupported && it.isNotBlank() }
            ?.let { listOf(it) }

        var messages = buildList {
            add(OllamaMessage(OllamaRole.SYSTEM, PromptBuilder.system()))
            addAll(history)
            add(
                OllamaMessage(
                    role = OllamaRole.USER,
                    content = PromptBuilder.userTurn(
                        task = taskText,
                        owner = request.owner,
                        repo = request.repo,
                        branch = branch,
                        treeText = FileTreeFormatter.format(tree.entries),
                        entryCount = tree.entries.size,
                    ),
                    images = userImages,
                )
            )
        }

        repeat(MAX_MODEL_STEPS) {
            emit(TurnEvent.Working("Thinking"))
            val raw = ollama.chat(model, messages)
            messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.ASSISTANT, raw))

            when (val action = AiActionParser.parse(raw)) {
                is AiAction.Reply -> {
                    chat.appendAiText(request.repoKey, request.sessionId, action.text)
                    emit(TurnEvent.Reply(action.text))
                    return@flow
                }

                is AiAction.ReadFile -> {
                    emit(TurnEvent.Working("Reading ${action.path}"))
                    emit(TurnEvent.ReadingFile(action.path))
                    chat.appendAiRead(request.repoKey, request.sessionId, action.path)

                    val file = github.fileContent(request.owner, request.repo, action.path, branch)
                    val context = when {
                        file == null -> PromptBuilder.fileNotFoundMessage(action.path)
                        file.isBinary -> PromptBuilder.binaryFileMessage(action.path)
                        else -> PromptBuilder.fileContentMessage(action.path, file)
                    }
                    messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.USER, context))
                }

                is AiAction.WriteFile -> {
                    emit(TurnEvent.Working("Preparing diff"))
                    val current = github.fileContent(request.owner, request.repo, action.path, branch)
                    val diff = LineDiffer.diff(current?.content.orEmpty(), action.content)
                    val change = PendingChange(
                        path = action.path,
                        oldContent = current?.content.orEmpty(),
                        newContent = action.content,
                        baseSha = current?.sha,
                        branch = branch,
                        commitMessage = action.commitMessage,
                        isNew = current == null,
                        additions = diff.additions,
                        removals = diff.removals,
                    )
                    val rowId = chat.appendAiWritePending(request.repoKey, request.sessionId, change)
                    emit(TurnEvent.ProposeWrite(rowId, change))

                    // Wait for the user's explicit decision. Approve -> commit
                    // to the working branch. Reject -> mark declined, no commit.
                    val approved = approval.first()
                    if (approved) {
                        emit(TurnEvent.Working("Committing ${action.path}"))
                        val result = github.commitFile(
                            owner = request.owner,
                            repo = request.repo,
                            path = action.path,
                            newContent = action.content,
                            branch = branch,
                            baseSha = change.baseSha,
                            commitMessage = change.commitMessage,
                        )
                        chat.markWrite(rowId, MessageStatus.APPROVED, result.newSha)
                        emit(TurnEvent.WriteCommitted(rowId, change))
                    } else {
                        chat.markWrite(rowId, MessageStatus.REJECTED, null)
                        emit(TurnEvent.WriteDeclined(rowId, change))
                    }
                    return@flow
                }

                is AiAction.CreatePullRequest -> {
                    emit(TurnEvent.Working("Opening pull request"))
                    val info = github.createPullRequest(
                        owner = request.owner,
                        repo = request.repo,
                        head = branch,
                        base = request.defaultBranch,
                        title = action.title,
                        body = action.body,
                    )
                    emit(TurnEvent.PullRequestCreated(info))
                    val context = "PULL REQUEST CREATED - #${info.number} \"${info.title}\"\n" +
                        "URL: ${info.htmlUrl}\n" +
                        "Head: $branch → base: ${request.defaultBranch}\n" +
                        "Tell the user the PR is ready and share the URL. Merging stays a manual step."
                    messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.USER, context))
                }

                is AiAction.CheckCiStatus -> {
                    val targetBranch = action.branchOverride
                        ?.takeIf { it.isNotBlank() }
                        ?: branch
                    emit(TurnEvent.Working("Checking CI on $targetBranch"))
                    val runs = github.listWorkflowRuns(
                        owner = request.owner,
                        repo = request.repo,
                        branch = targetBranch,
                    )
                    val latest = runs.firstOrNull()
                    emit(TurnEvent.CiStatus(latest))
                    val context = if (latest == null) {
                        "CI STATUS - no GitHub Actions runs found for branch `$targetBranch`. " +
                            "Tell the user there is no CI history yet for this branch."
                    } else {
                        buildString {
                            append("CI STATUS - branch `$targetBranch`:\n")
                            append("- Workflow: ${latest.name.ifBlank { "(unnamed)" }}\n")
                            append("- Status: ${latest.status}\n")
                            append("- Conclusion: ${latest.conclusion ?: "(still running)"}\n")
                            latest.htmlUrl?.let { append("- URL: $it\n") }
                            append("Summarize this for the user in plain language (one check is enough).")
                        }
                    }
                    messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.USER, context))
                }
            }
        }

        val exhausted = "I've reached the maximum number of steps for this request. " +
            "The conversation is saved — send another message and I'll pick up where I left off."
        chat.appendAiText(request.repoKey, request.sessionId, exhausted)
        emit(TurnEvent.Reply(exhausted))
    }.catch { error ->
        if (error is CancellationException) throw error
        val appError = when (error) {
            is AppError -> error
            else -> AppError.Network(
                "Something went wrong: ${error.message?.takeIf { it.isNotBlank() } ?: "unexpected error"}"
            )
        }
        emit(TurnEvent.Error(appError))
    }

    /**
     * Plain conversational turn — no GitHub tools, no JSON action schema.
     * History + user message → Ollama → free-form markdown reply.
     */
    private suspend fun FlowCollector<TurnEvent>.runGeneralTurn(
        request: TurnRequest,
        model: String,
    ) {
        emit(TurnEvent.Working("Thinking"))
        val history = chat.recentMessages(request.repoKey, request.sessionId, limit = 16)
            .filter { it.kind == MessageKind.TEXT && !it.text.isNullOrBlank() }
            .dropLast(1)
            .map {
                OllamaMessage(
                    role = if (it.role == ChatRole.USER) OllamaRole.USER else OllamaRole.ASSISTANT,
                    content = it.text.orEmpty(),
                )
            }

        val visionSupported = PromptBuilder.modelSupportsVision(model)
        val attachment = request.attachment
        val attachedText = attachment?.textContent
        val attachedImageB64 = attachment?.imageBase64
        val userContent = buildString {
            when {
                attachment == null -> Unit
                !attachedText.isNullOrEmpty() -> {
                    append(PromptBuilder.attachedFileMessage(attachment.displayName, attachedText))
                    append("\n\n")
                }
                attachment.isImage -> {
                    append(PromptBuilder.attachedImageMessage(attachment.displayName, visionSupported))
                    append("\n\n")
                }
            }
            append(request.userText)
        }
        val userImages = attachedImageB64
            ?.takeIf { visionSupported && it.isNotBlank() }
            ?.let { listOf(it) }

        val messages = PromptBuilder.cap(
            buildList {
                add(OllamaMessage(OllamaRole.SYSTEM, PromptBuilder.generalSystem()))
                addAll(history)
                add(
                    OllamaMessage(
                        role = OllamaRole.USER,
                        content = userContent,
                        images = userImages,
                    ),
                )
            },
        )
        val raw = ollama.chat(model, messages)
        // Prefer plain text. Only unwrap when the model still emits the JSON
        // tool contract (common if the user just left a repo chat).
        val text = unwrapGeneralReply(raw)
        chat.appendAiText(request.repoKey, request.sessionId, text)
        emit(TurnEvent.Reply(text))
    }

    private fun unwrapGeneralReply(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "I didn't have anything to say — try again?"
        // Heuristic: only attempt schema unwrap when it looks like our JSON.
        val looksLikeToolJson = trimmed.startsWith("{") &&
            (trimmed.contains("\"action\"") || trimmed.contains("'action'"))
        if (!looksLikeToolJson) return trimmed
        return when (val action = AiActionParser.parse(trimmed)) {
            is AiAction.Reply -> action.text.ifBlank { trimmed }
            else -> trimmed
        }
    }

    private companion object {
        const val MAX_MODEL_STEPS = 10
    }
}
