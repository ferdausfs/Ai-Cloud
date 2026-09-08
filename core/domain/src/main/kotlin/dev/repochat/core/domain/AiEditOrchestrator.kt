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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
    private val llm: LlmService,
    private val github: GithubService,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
) : AiTurnRunner {

    override fun runTurn(request: TurnRequest, approval: Flow<Boolean>): Flow<TurnEvent> = channelFlow {
        val snap = settings.current()
        val active = snap.activeLlmOrFirst()
        val model = active?.modelName?.trim().orEmpty().ifBlank { snap.modelName.trim() }
        if (model.isEmpty() && snap.llmConnectionsOrdered().isEmpty() && snap.ollamaKey.isBlank()) {
            throw AppError.Configuration(
                "No AI provider configured yet. Add Ollama or an OpenAI-compatible connection in Settings."
            )
        }

        if (request.isGeneral) {
            runGeneralTurn(request, model)
            return@channelFlow
        }

        send(TurnEvent.Working("Checking working branch"))
        val branch = github.ensureWorkingBranch(
            owner = request.owner,
            repo = request.repo,
            sessionId = request.sessionId,
            defaultBranch = request.defaultBranch,
        )
        chat.updateWorkingBranch(request.repoKey, branch)

        send(TurnEvent.Working("Fetching repository tree"))
        val tree = github.fileTree(request.owner, request.repo, branch)
        send(TurnEvent.TreeReady(tree.truncated))

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
        val userImageMimes = userImages?.let {
            listOf(
                attachment?.mimeType?.takeIf { m -> m.startsWith("image/") } ?: "image/jpeg",
            )
        }

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
                    imageMimeTypes = userImageMimes,
                )
            )
        }

        repeat(MAX_MODEL_STEPS) {
            send(TurnEvent.Working("Thinking"))
            val result = llm.chat(
                messages = messages,
                jsonMode = true,
                preferredConnectionId = request.preferredConnectionId,
            )
            result.fellBackFrom?.let { from ->
                val note = "$from hit a rate limit — switched to ${result.providerLabel} for this response"
                chat.appendAiText(request.repoKey, request.sessionId, "↔ $note")
                send(TurnEvent.ProviderNote(note))
            }
            val raw = result.text
            messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.ASSISTANT, raw))

            when (val action = AiActionParser.parse(raw)) {
                is AiAction.Reply -> {
                    chat.appendAiText(request.repoKey, request.sessionId, action.text)
                    send(TurnEvent.Reply(action.text))
                    return@channelFlow
                }

                is AiAction.ReadFile -> {
                    send(TurnEvent.Working("Reading ${action.path}"))
                    send(TurnEvent.ReadingFile(action.path))
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
                    send(TurnEvent.Working("Preparing diff"))
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
                    send(TurnEvent.ProposeWrite(rowId, change))

                    // Wait for the user's explicit decision. Approve -> commit
                    // to the working branch. Reject -> mark declined, no commit.
                    val approved = approval.first()
                    if (approved) {
                        send(TurnEvent.Working("Committing ${action.path}"))
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
                        send(TurnEvent.WriteCommitted(rowId, change, newSha = result.newSha))
                    } else {
                        chat.markWrite(rowId, MessageStatus.REJECTED, null)
                        send(TurnEvent.WriteDeclined(rowId, change))
                    }
                    return@channelFlow
                }

                is AiAction.CreatePullRequest -> {
                    send(TurnEvent.Working("Opening pull request"))
                    val info = github.createPullRequest(
                        owner = request.owner,
                        repo = request.repo,
                        head = branch,
                        base = request.defaultBranch,
                        title = action.title,
                        body = action.body,
                    )
                    send(TurnEvent.PullRequestCreated(info))
                    val context = "PULL REQUEST CREATED - #${info.number} \"${info.title}\"\n" +
                        "URL: ${info.htmlUrl}\n" +
                        "Head: $branch → base: ${request.defaultBranch}\n" +
                        "Tell the user the PR is ready and share the URL. Merging stays a manual step."
                    messages = PromptBuilder.cap(messages + OllamaMessage(OllamaRole.USER, context))
                }

                is AiAction.CheckCiStatus -> {
                    // CI is always checked on THIS session's working branch.
                    // A model-supplied branch override (from a confused or
                    // injected response) could point at another branch —
                    // e.g. main — and mislead the user or the auto-fix loop,
                    // so it is deliberately ignored here (AUD-010).
                    val targetBranch = branch
                    send(TurnEvent.Working("Checking CI on $targetBranch"))
                    val runs = github.listWorkflowRuns(
                        owner = request.owner,
                        repo = request.repo,
                        branch = targetBranch,
                    )
                    val latest = runs.firstOrNull()
                    send(TurnEvent.CiStatus(latest))
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
        send(TurnEvent.Reply(exhausted))
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
     * History + user message → LLM → free-form markdown reply, streamed to
     * the UI as it is generated (conflated pump coalesces SSE bursts).
     */
    private suspend fun ProducerScope<TurnEvent>.runGeneralTurn(
        request: TurnRequest,
        model: String,
    ) {
        send(TurnEvent.Working("Thinking"))
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
        val userImageMimes = userImages?.let {
            listOf(
                attachment?.mimeType?.takeIf { m -> m.startsWith("image/") } ?: "image/jpeg",
            )
        }

        val messages = PromptBuilder.cap(
            buildList {
                add(OllamaMessage(OllamaRole.SYSTEM, PromptBuilder.generalSystem()))
                addAll(history)
                add(
                    OllamaMessage(
                        role = OllamaRole.USER,
                        content = userContent,
                        images = userImages,
                        imageMimeTypes = userImageMimes,
                    ),
                )
            },
        )
        // Stream the reply as it is generated so plain conversations feel
        // live (ChatGPT/HuggingChat-style). A conflated channel + child pump
        // coalesces SSE bursts and emits from the channelFlow scope.
        val deltaChannel = Channel<String>(Channel.CONFLATED)
        val pump = launch {
            for (cumulative in deltaChannel) {
                send(TurnEvent.ReplyDelta(cumulative))
            }
        }
        val result = try {
            llm.chatStreaming(
                messages = messages,
                jsonMode = false,
                preferredConnectionId = request.preferredConnectionId,
            ) { cumulative ->
                deltaChannel.trySend(cumulative)
            }
        } finally {
            deltaChannel.close()
        }
        pump.join()
        result.fellBackFrom?.let { from ->
            val note = "$from hit a rate limit — switched to ${result.providerLabel} for this response"
            chat.appendAiText(request.repoKey, request.sessionId, "↔ $note")
            send(TurnEvent.ProviderNote(note))
        }
        // Prefer plain text. Only unwrap when the model still emits the JSON
        // tool contract (common if the user just left a repo chat).
        val text = unwrapGeneralReply(result.text)
        chat.appendAiText(request.repoKey, request.sessionId, text)
        send(TurnEvent.Reply(text))
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
