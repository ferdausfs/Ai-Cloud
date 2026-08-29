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
        val taskText = buildString {
            when {
                attachment == null -> Unit
                !attachment.textContent.isNullOrEmpty() -> {
                    append(PromptBuilder.attachedFileMessage(attachment.displayName, attachment.textContent))
                    append("\n\n")
                }
                attachment.isImage -> {
                    append(PromptBuilder.attachedImageMessage(attachment.displayName, visionSupported))
                    append("\n\n")
                }
            }
            append(request.userText)
        }
        val userImages = attachment
            ?.imageBase64
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

    private companion object {
        const val MAX_MODEL_STEPS = 10
    }
}
