package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.AutoFixEvent
import dev.repochat.core.model.TurnEvent
import dev.repochat.core.model.TurnRequest
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Autonomous "edit → commit → wait for CI → fix from real logs" loop.
 *
 * Reuses [AiTurnRunner] for each attempt (read/write/commit stay unchanged) and
 * [GithubService] for run/job/log polling. Writes are auto-approved so the loop
 * can finish without the user babysitting every diff — the user opted in via
 * the chat "Auto-fix until CI passes" toggle.
 *
 * Must run under [dev.repochat.turn.AiTurnCoordinator] + FGS; CI can take minutes.
 */
@Singleton
class AutoFixLoop @Inject constructor(
    private val turnRunner: AiTurnRunner,
    private val github: GithubService,
    private val chat: ChatRepository,
) {
    /** Overridable for unit tests (production uses the companion defaults). */
    internal var ciWaitBudgetMs: Long = CI_WAIT_BUDGET_MS
    internal var ciPollInitialMs: Long = CI_POLL_INITIAL_MS
    internal var ciPollMaxMs: Long = CI_POLL_MAX_MS
    internal var logRetryDelayMs: Long = 2_000L
    /** Hard cap on CI polls (also bounds unit tests that use virtual delay). */
    internal var ciMaxPolls: Int = CI_MAX_POLLS

    fun run(
        request: TurnRequest,
        maxAttempts: Int = request.autoFixMaxAttempts.coerceIn(1, 10),
    ): Flow<TurnEvent> = channelFlow {
        val history = mutableListOf<String>()
        var prompt = request.userText
        var lastLog: String? = null
        var attempt = 0
        // Seed: ignore runs that already finished before this loop started so we
        // don't treat a stale green/red as the result of the next commit.
        var baselineRunId: Long? = null
        try {
            val branchHint = request.workingBranch
            if (!branchHint.isNullOrBlank()) {
                baselineRunId = github.listWorkflowRuns(
                    owner = request.owner,
                    repo = request.repo,
                    branch = branchHint,
                    perPage = 1,
                ).firstOrNull()?.id
            }
        } catch (_: Exception) {
            // Best-effort; loop still works without a baseline.
        }

        while (attempt < maxAttempts) {
            attempt++
            val started = AutoFixEvent.AttemptStarted(attempt, maxAttempts)
            send(TurnEvent.AutoFixProgress(started))
            postStatus(request, formatAttemptStarted(attempt, maxAttempts))

            // Auto-approve every write_file so the loop stays autonomous.
            val approval = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
            val attemptRequest = request.copy(userText = prompt, autoFixUntilCiGreen = false)
            var committedSummary: String? = null
            var workingBranch: String? = request.workingBranch
            var turnError: AppError? = null
            var userDeclined = false

            turnRunner.runTurn(attemptRequest, approval).collect { event ->
                when (event) {
                    is TurnEvent.ProposeWrite -> {
                        // Surface the diff UI, then approve immediately.
                        send(event)
                        approval.tryEmit(true)
                    }
                    is TurnEvent.WriteCommitted -> {
                        committedSummary = "${event.change.path}: ${event.change.commitMessage}"
                        workingBranch = event.change.branch
                        send(event)
                        val committed = AutoFixEvent.Committed(attempt, committedSummary!!)
                        send(TurnEvent.AutoFixProgress(committed))
                        postStatus(
                            request,
                            "Attempt $attempt/$maxAttempts — committed `${event.change.path}` " +
                                "(${event.change.commitMessage}). Waiting on CI…",
                        )
                    }
                    is TurnEvent.WriteDeclined -> {
                        userDeclined = true
                        send(event)
                    }
                    is TurnEvent.Error -> {
                        turnError = event.error
                        send(event)
                    }
                    is TurnEvent.Reply -> {
                        // Model answered without a write — keep the reply visible,
                        // but if we never committed we can't wait on CI for this attempt.
                        send(event)
                    }
                    is TurnEvent.Working,
                    is TurnEvent.TreeReady,
                    is TurnEvent.ReadingFile,
                    is TurnEvent.PullRequestCreated,
                    is TurnEvent.CiStatus,
                    is TurnEvent.AutoFixProgress,
                    -> send(event)
                }
            }

            if (userDeclined) {
                val msg = "Auto-fix stopped — a change was declined."
                postStatus(request, msg)
                send(
                    TurnEvent.AutoFixProgress(
                        AutoFixEvent.GaveUp(attempt, history + msg, lastLog),
                    ),
                )
                return@channelFlow
            }

            turnError?.let { err ->
                history += "Attempt $attempt: turn error — ${err.userMessage}"
                lastLog = err.userMessage
                // Retry with the error context unless we're out of attempts.
                if (attempt >= maxAttempts) {
                    finishGaveUp(request, attempt, history, lastLog)
                    return@channelFlow
                }
                prompt = buildFixPrompt(request.userText, history, err.userMessage)
                continue
            }

            if (committedSummary == null) {
                // Model replied without committing — treat as "no code change this
                // attempt" and stop rather than spinning on the same reply.
                history += "Attempt $attempt: model replied without a commit"
                val summary = buildString {
                    append("I finished attempt $attempt/$maxAttempts without committing a change. ")
                    append("Auto-fix needs a `write_file` commit so CI can run on the working branch. ")
                    append("Want me to keep trying with a more specific instruction, or will you look at it?")
                }
                postStatus(request, summary)
                send(
                    TurnEvent.AutoFixProgress(
                        AutoFixEvent.GaveUp(attempt, history, lastLog),
                    ),
                )
                send(TurnEvent.Reply(summary))
                return@channelFlow
            }

            history += "Attempt $attempt: committed $committedSummary"
            val branch = workingBranch
                ?: "ai-chat/${request.sessionId}"

            // ---- Poll CI -------------------------------------------------
            send(TurnEvent.Working("Waiting on CI (attempt $attempt/$maxAttempts)"))
            send(
                TurnEvent.AutoFixProgress(
                    AutoFixEvent.CiPending(attempt, null),
                ),
            )

            val run = waitForCi(
                owner = request.owner,
                repo = request.repo,
                branch = branch,
                baselineRunId = baselineRunId,
                onTick = { latest ->
                    send(TurnEvent.CiStatus(latest))
                    send(TurnEvent.AutoFixProgress(AutoFixEvent.CiPending(attempt, latest)))
                    send(
                        TurnEvent.Working(
                            "CI ${latest?.status ?: "pending"} " +
                                "(attempt $attempt/$maxAttempts)",
                        ),
                    )
                },
            )

            if (run == null) {
                val note = "No CI run appeared for `$branch` within the wait window."
                history += "Attempt $attempt: $note"
                lastLog = note
                if (attempt >= maxAttempts) {
                    finishGaveUp(request, attempt, history, lastLog)
                    return@channelFlow
                }
                prompt = buildFixPrompt(
                    originalTask = request.userText,
                    history = history,
                    logExcerpt = "$note\nConfirm the branch has a GitHub Actions workflow that " +
                        "triggers on push, then retry the fix.",
                )
                continue
            }

            baselineRunId = run.id
            send(TurnEvent.CiStatus(run))

            when (run.conclusion) {
                "success" -> {
                    val passed = AutoFixEvent.CiPassed(attempt, run)
                    send(TurnEvent.AutoFixProgress(passed))
                    val msg = buildString {
                        append("✅ CI is green on attempt $attempt/$maxAttempts. ")
                        append(run.summarize())
                        run.htmlUrl?.let { append("\n$it") }
                        append("\n\nOriginal task: ${request.userText}")
                    }
                    postStatus(request, msg)
                    send(TurnEvent.Reply(msg))
                    return@channelFlow
                }
                "cancelled", "skipped" -> {
                    val note = "CI ${run.conclusion} on attempt $attempt."
                    history += note
                    lastLog = note
                    if (attempt >= maxAttempts) {
                        finishGaveUp(request, attempt, history, lastLog)
                        return@channelFlow
                    }
                    prompt = buildFixPrompt(request.userText, history, note)
                    continue
                }
                else -> {
                    // failure (or unexpected conclusion) — pull real logs.
                    val excerpt = fetchFailureLog(
                        owner = request.owner,
                        repo = request.repo,
                        run = run,
                    )
                    lastLog = excerpt
                    history += "Attempt $attempt: CI failed — ${shortReason(excerpt)}"
                    send(
                        TurnEvent.AutoFixProgress(
                            AutoFixEvent.CiFailed(attempt, run, excerpt),
                        ),
                    )
                    postStatus(
                        request,
                        "Attempt $attempt/$maxAttempts — CI failed. Reading logs and fixing…\n\n" +
                            "```\n${excerpt.take(1_500)}\n```",
                    )
                    if (attempt >= maxAttempts) {
                        finishGaveUp(request, attempt, history, lastLog)
                        return@channelFlow
                    }
                    prompt = buildFixPrompt(request.userText, history, excerpt)
                }
            }
        }

        finishGaveUp(request, attempt, history, lastLog)
    }.catch { error ->
        if (error is CancellationException) throw error
        val appError = when (error) {
            is AppError -> error
            else -> AppError.Network(
                "Auto-fix loop failed: ${error.message?.takeIf { it.isNotBlank() } ?: "unexpected error"}",
            )
        }
        emit(TurnEvent.AutoFixProgress(AutoFixEvent.Error(appError)))
        emit(TurnEvent.Error(appError))
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<TurnEvent>.finishGaveUp(
        request: TurnRequest,
        attemptsMade: Int,
        history: List<String>,
        lastLog: String?,
    ) {
        val gaveUp = AutoFixEvent.GaveUp(attemptsMade, history.toList(), lastLog)
        send(TurnEvent.AutoFixProgress(gaveUp))
        val summary = buildGaveUpMessage(request.userText, attemptsMade, history, lastLog)
        postStatus(request, summary)
        send(TurnEvent.Reply(summary))
    }

    private suspend fun postStatus(request: TurnRequest, text: String) {
        chat.appendAiText(request.repoKey, request.sessionId, text)
    }

    /**
     * Poll [GithubService.listWorkflowRuns] until a run newer than [baselineRunId]
     * reaches a terminal conclusion, or the overall wait budget is spent.
     */
    private suspend fun waitForCi(
        owner: String,
        repo: String,
        branch: String,
        baselineRunId: Long?,
        onTick: suspend (WorkflowRunInfo?) -> Unit,
    ): WorkflowRunInfo? {
        val deadline = System.currentTimeMillis() + ciWaitBudgetMs
        var delayMs = ciPollInitialMs
        var lastSeen: WorkflowRunInfo? = null
        var candidate: WorkflowRunInfo? = null
        var polls = 0

        while (polls < ciMaxPolls && System.currentTimeMillis() < deadline) {
            polls++
            val runs = try {
                github.listWorkflowRuns(owner, repo, branch, perPage = 5)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            // Prefer a run newer than the baseline; fall back to the latest run
            // once it looks like it started after we committed.
            candidate = runs.firstOrNull { baselineRunId == null || it.id != baselineRunId }
                ?: runs.firstOrNull()
            if (candidate != null && candidate.id == baselineRunId) {
                // Still looking at the pre-commit run — keep waiting for a new one.
                candidate = null
            }
            lastSeen = candidate ?: lastSeen
            onTick(candidate)

            val done = candidate?.takeIf {
                it.status == "completed" || !it.conclusion.isNullOrBlank()
            }
            if (done != null) return done

            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(ciPollMaxMs)
        }
        // Return whatever we last saw (may still be in_progress) so the caller
        // can decide; null means nothing ever appeared.
        return lastSeen?.takeIf {
            it.status == "completed" || !it.conclusion.isNullOrBlank()
        }
    }

    /**
     * Fetch jobs for [run], pick a failed job, download its log, truncate to
     * the last [LOG_EXCERPT_CHARS] characters (errors live near the end).
     */
    private suspend fun fetchFailureLog(
        owner: String,
        repo: String,
        run: WorkflowRunInfo,
    ): String {
        val jobs: List<WorkflowJobInfo> = try {
            github.listJobsForRun(owner, repo, run.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return buildString {
                append("Could not list jobs for run #${run.id}: ")
                append(e.message ?: e::class.simpleName)
                run.htmlUrl?.let { append("\nRun URL: $it") }
            }
        }

        val failed = jobs.firstOrNull { it.conclusion == "failure" }
            ?: jobs.firstOrNull { it.steps.any { s -> s.conclusion == "failure" } }
            ?: jobs.firstOrNull()

        val failedSteps = failed?.steps
            ?.filter { it.conclusion == "failure" }
            ?.joinToString { "#${it.number} ${it.name}" }
            .orEmpty()

        if (failed == null) {
            return buildString {
                append("CI failed (${run.summarize()}) but no job details were returned.")
                run.htmlUrl?.let { append("\nRun URL: $it") }
            }
        }

        val rawLog = try {
            // Logs can take a few seconds to become available after conclusion.
            withTimeoutOrNull((logRetryDelayMs * 8).coerceAtLeast(1_000L)) {
                var text: String? = null
                repeat(4) { i ->
                    try {
                        text = github.getJobLogs(owner, repo, failed.id)
                        if (!text.isNullOrBlank()) return@withTimeoutOrNull text
                    } catch (_: Exception) {
                        // 404 while GitHub finishes packaging logs — retry.
                    }
                    delay(logRetryDelayMs * (i + 1))
                }
                text
            }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }

        val excerpt = truncateTail(rawLog, LOG_EXCERPT_CHARS)
        return buildString {
            append("Workflow: ${run.name.ifBlank { "(unnamed)" }}\n")
            append("Job: ${failed.name.ifBlank { "#${failed.id}" }}")
            if (failed.conclusion != null) append(" (${failed.conclusion})")
            append('\n')
            if (failedSteps.isNotBlank()) append("Failed steps: $failedSteps\n")
            run.htmlUrl?.let { append("Run URL: $it\n") }
            append('\n')
            if (excerpt.isBlank()) {
                append("(Job log was empty or not yet available.)")
            } else {
                append("---- log (tail) ----\n")
                append(excerpt)
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 5
        /** Tail of the job log fed back to the model (errors sit at the end). */
        const val LOG_EXCERPT_CHARS = 8_000
        private const val CI_WAIT_BUDGET_MS = 12 * 60 * 1_000L // ~12 minutes
        private const val CI_POLL_INITIAL_MS = 15_000L
        private const val CI_POLL_MAX_MS = 45_000L
        private const val CI_MAX_POLLS = 40

        fun truncateTail(text: String, maxChars: Int = LOG_EXCERPT_CHARS): String {
            if (text.length <= maxChars) return text
            return "…(log truncated to last $maxChars chars)…\n" +
                text.substring(text.length - maxChars)
        }

        fun buildFixPrompt(
            originalTask: String,
            history: List<String>,
            logExcerpt: String,
        ): String = buildString {
            append(originalTask.trim())
            append("\n\n---\n")
            append("AUTO-FIX CONTEXT: previous attempt(s) did not leave CI green.\n")
            if (history.isNotEmpty()) {
                append("History:\n")
                history.takeLast(5).forEach { append("- ").append(it).append('\n') }
            }
            append("The previous attempt failed CI with this error:\n")
            append(logExcerpt)
            append("\n\nFix it. Use read_file / write_file as needed. ")
            append("Commit a real fix on the working branch — do not claim success without changing code.")
        }

        fun buildGaveUpMessage(
            originalTask: String,
            attemptsMade: Int,
            history: List<String>,
            lastLog: String?,
        ): String = buildString {
            append("I couldn't get CI green after $attemptsMade ")
            append(if (attemptsMade == 1) "attempt" else "attempts")
            append(" for: \"$originalTask\".\n\n")
            append("What I tried:\n")
            history.forEachIndexed { i, line -> append("${i + 1}. $line\n") }
            if (!lastLog.isNullOrBlank()) {
                append("\nLast CI error (excerpt):\n```\n")
                append(lastLog.take(2_000))
                append("\n```\n")
            }
            append("\nWant me to keep trying, or do you want to look at it?")
        }

        fun formatAttemptStarted(attempt: Int, max: Int): String =
            "Auto-fix attempt $attempt/$max — editing and committing…"

        fun shortReason(logExcerpt: String): String {
            val line = logExcerpt.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .lastOrNull { it.contains("error", ignoreCase = true) || it.contains("FAILED") }
                ?: logExcerpt.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            return (line ?: "see log").take(160)
        }
    }
}
