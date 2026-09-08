package dev.repochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.R
import dev.repochat.core.domain.AutoFixLoop
import dev.repochat.core.domain.ChatRepository
import dev.repochat.core.domain.CreatePullRequestUseCase
import dev.repochat.core.domain.GithubService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ChatAttachment
import dev.repochat.core.model.ChatMessage
import dev.repochat.core.model.ChatMode
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.PendingChange
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoSession
import dev.repochat.core.model.RepoSummary
import dev.repochat.core.model.TurnRequest
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
import dev.repochat.turn.AiTurnCoordinator
import dev.repochat.turn.AiTurnSnackbar
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SnackbarEvent(
    val id: Long = 0,
    val textRes: Int = 0,
    val args: List<Any> = emptyList(),
)

sealed interface PrState {
    data object None : PrState
    data object Creating : PrState
    data class Ready(val info: PullRequestInfo) : PrState
    data class Failed(val message: String) : PrState
}

/**
 * Lightweight preview of a file the user picked but has not yet sent.
 * The heavy content (text / base64) is loaded only at send time.
 */
data class PendingAttachment(
    val uriString: String,
    val displayName: String,
    val mimeType: String?,
    val isImage: Boolean,
)

data class ChatUiState(
    val session: RepoSession? = null,
    val messages: List<ChatMessage> = emptyList(),
    val typing: Boolean = false,
    val workingStep: String = "",
    /** Recent agent steps (max 4, oldest first) for the live activity trail. */
    val stepTrail: List<String> = emptyList(),
    /** Cumulative streamed reply text while a plain conversational turn runs. */
    val streamText: String = "",
    val approvalPending: Boolean = false,
    val approving: Boolean = false,
    val pendingWriteMessageId: Long? = null,
    val liveChange: PendingChange? = null,
    val treeTruncated: Boolean = false,
    val error: AppError? = null,
    val canRetry: Boolean = false,
    val snackbar: SnackbarEvent = SnackbarEvent(),
    val prState: PrState = PrState.None,
    val pendingAttachment: PendingAttachment? = null,
    /** Latest known Actions run for the working branch. */
    val ciStatus: WorkflowRunInfo? = null,
    /** Last observed failed run — drives the in-chat "build failed" banner. */
    val ciFailure: WorkflowRunInfo? = null,
    /** Opt-in per message: run AutoFixLoop until CI is green (or attempts exhausted). */
    val autoFixUntilCiGreen: Boolean = false,
    val autoFixActive: Boolean = false,
    val autoFixAttempt: Int = 0,
    val autoFixMaxAttempts: Int = 0,
    val llmProviders: List<ServiceConnection> = emptyList(),
    val activeProviderId: String? = null,
    val activeProviderLabel: String = "",
    /** In-chat "attach repo" sheet. */
    val repoPickerOpen: Boolean = false,
    val repoOptions: List<RepoSummary> = emptyList(),
    val repoOptionsLoading: Boolean = false,
    val repoOptionsError: String? = null,
    /** In-app CI build sheet (jobs + logs). */
    val ciSheetOpen: Boolean = false,
    val ciJobs: List<WorkflowJobInfo> = emptyList(),
    val ciJobsLoading: Boolean = false,
    val ciJobsError: String? = null,
    val selectedJob: WorkflowJobInfo? = null,
    val jobLog: String? = null,
    val jobLogLoading: Boolean = false,
    val jobLogError: String? = null,
)

/**
 * UI-facing ViewModel. Turn execution is delegated to [AiTurnCoordinator] so
 * work survives Activity/ViewModel teardown (paired with AiTurnService FGS).
 * Chat history still comes from Room — returning to the app shows completed turns.
 *
 * Unified chat model: every conversation is a normal agent chat; attaching a
 * repo context (from Home, Repo detail, or the in-chat sheet) enables the
 * GitHub tools for subsequent turns. There is no separate "mode" choice.
 */
@HiltViewModel
@kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val createPullRequest: CreatePullRequestUseCase,
    private val turnCoordinator: AiTurnCoordinator,
    private val settingsRepository: SettingsRepository,
    private val githubService: GithubService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var boundKey: String = ""

    /** Guards against duplicate start() calls with identical navigation args. */
    private var lastStartKey: String? = null

    private var messageJob: Job? = null
    private var turnObserveJob: Job? = null
    private var ciProbeJob: Job? = null
    private var snackbarCounter = 0L

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                val ordered = s.llmConnectionsOrdered()
                val active = s.activeLlmOrFirst()
                _uiState.update {
                    it.copy(
                        llmProviders = ordered,
                        activeProviderId = active?.id,
                        activeProviderLabel = active?.label?.ifBlank { active.modelName }
                            ?: s.modelName.ifBlank { "—" },
                    )
                }
            }
        }
    }

    fun setActiveProvider(id: String) {
        viewModelScope.launch {
            val cur = settingsRepository.current()
            settingsRepository.save(cur.copy(activeProviderId = id))
        }
    }

    /**
     * Opens (or binds) the conversation.
     *  - [repoKey] non-blank → reopen that stored conversation (any kind).
     *  - else [owner]/[repo] non-blank → repo-pre-attached conversation.
     *  - else → a fresh unified chat (plain agent; repo attachable in-chat).
     *
     * @param mode deprecated — kept for navigation compatibility, ignored.
     */
    fun start(
        owner: String,
        repo: String,
        defaultBranch: String,
        repoKey: String = "",
        mode: String = "REPO",
    ) {
        val startKey = "$owner|$repo|$defaultBranch|$repoKey"
        if (startKey == lastStartKey) return
        lastStartKey = startKey

        val resolvedKey = when {
            repoKey.isNotBlank() -> repoKey
            owner.isNotBlank() && repo.isNotBlank() -> "$owner/$repo"
            else -> ""
        }
        boundKey = resolvedKey
        _uiState.value = ChatUiState()

        messageJob?.cancel()
        turnObserveJob?.cancel()
        ciProbeJob?.cancel()

        messageJob = viewModelScope.launch {
            val session = if (resolvedKey.isNotBlank()) {
                chatRepository.getSession(resolvedKey) ?: when {
                    owner.isNotBlank() && repo.isNotBlank() ->
                        chatRepository.ensureSession(owner, repo, defaultBranch)
                    else -> chatRepository.createGeneralSession().let {
                        // repoKey pointed nowhere — fall back to a fresh chat.
                        boundKey = it.repoKey
                        it
                    }
                }
            } else {
                val created = chatRepository.createGeneralSession()
                boundKey = created.repoKey
                created
            }
            if (boundKey.isBlank()) boundKey = session.repoKey
            _uiState.update { it.copy(session = session) }

            // Resolve proposals left PENDING by a process death / crash
            // (AUD-003): with no live turn for this conversation the approval
            // gate can never fire again, so the card would be stuck on
            // "Pending review" forever. Be honest about the uncertainty —
            // an approve-tap that died mid-flight may still have landed.
            val liveForThisRepo = turnCoordinator.state.value.let {
                it.active && it.repoKey == boundKey
            }
            if (!liveForThisRepo) {
                val resolved = chatRepository.rejectStalePendingWrites(boundKey, session.sessionId)
                if (resolved > 0) {
                    chatRepository.appendAiText(
                        boundKey,
                        session.sessionId,
                        "Closed $resolved proposal(s) left pending by a restart or error. " +
                            "If you had just approved one, check the working branch — " +
                            "that commit may still have landed.",
                    )
                }
            }

            if (!session.isGeneral) refreshCiStatus()

            chatRepository.session(boundKey)
                .filterNotNull()
                .flatMapLatest { s ->
                    _uiState.update { it.copy(session = s) }
                    chatRepository.messages(boundKey, s.sessionId)
                }
                .collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
        }

        // Mirror coordinator live state for this conversation (and any in-flight
        // turn that continued while we were backgrounded).
        turnObserveJob = viewModelScope.launch {
            turnCoordinator.state.collect { live ->
                val bound = boundKey
                if (bound.isNotEmpty() && live.repoKey.isNotEmpty() && live.repoKey != bound) {
                    return@collect
                }
                _uiState.update { ui ->
                    val same = bound.isEmpty() || live.repoKey.isEmpty() || live.repoKey == bound
                    val prInfo = live.prInfo
                    ui.copy(
                        typing = if (same) live.typing else ui.typing,
                        workingStep = if (same) live.workingStep else ui.workingStep,
                        stepTrail = if (same) live.stepTrail else ui.stepTrail,
                        streamText = if (same) live.streamText else ui.streamText,
                        approvalPending = if (same) live.approvalPending else ui.approvalPending,
                        approving = if (same) live.approving else ui.approving,
                        pendingWriteMessageId = if (same) live.pendingWriteMessageId else ui.pendingWriteMessageId,
                        liveChange = if (same) live.liveChange else ui.liveChange,
                        treeTruncated = if (same) (live.treeTruncated || ui.treeTruncated) else ui.treeTruncated,
                        error = if (same) live.error else ui.error,
                        canRetry = if (same) live.canRetry else ui.canRetry,
                        ciStatus = if (same) (live.ciStatus ?: ui.ciStatus) else ui.ciStatus,
                        prState = if (same && prInfo != null) PrState.Ready(prInfo) else ui.prState,
                        autoFixActive = if (same) live.autoFixActive else ui.autoFixActive,
                        autoFixAttempt = if (same) live.autoFixAttempt else ui.autoFixAttempt,
                        autoFixMaxAttempts = if (same) live.autoFixMaxAttempts else ui.autoFixMaxAttempts,
                    )
                }
                live.snackbar?.let { snack ->
                    when (snack) {
                        is AiTurnSnackbar.Committed -> {
                            showSnackbar(R.string.chat_committed_to, snack.branch)
                            // A single-turn commit just landed — watch its CI.
                            if (!turnCoordinator.state.value.autoFixActive) scheduleCiProbes()
                        }
                        AiTurnSnackbar.Declined -> showSnackbar(R.string.chat_declined)
                    }
                    turnCoordinator.consumeSnackbar()
                }
            }
        }
    }

    /* ----------------------- unified repo context ----------------------- */

    /** Attaches (or replaces) the repo context of this conversation. */
    fun attachRepo(selected: RepoSummary) {
        val session = _uiState.value.session ?: return
        closeRepoPicker()
        viewModelScope.launch {
            chatRepository.updateRepoContext(
                repoKey = boundKey.ifBlank { session.repoKey },
                owner = selected.owner,
                repo = selected.name,
                defaultBranch = selected.defaultBranch,
                isRepo = true,
            )
            _uiState.update {
                it.copy(
                    session = it.session?.copy(
                        owner = selected.owner,
                        repo = selected.name,
                        defaultBranch = selected.defaultBranch,
                        mode = ChatMode.REPO,
                    ),
                    ciStatus = null,
                    ciFailure = null,
                    autoFixUntilCiGreen = it.autoFixUntilCiGreen,
                )
            }
            refreshCiStatus()
        }
    }

    /** Detaches the repo context — subsequent turns are plain agent chat. */
    fun detachRepo() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepository.updateRepoContext(
                repoKey = boundKey.ifBlank { session.repoKey },
                owner = "",
                repo = "",
                defaultBranch = "",
                isRepo = false,
            )
            _uiState.update {
                it.copy(
                    session = it.session?.copy(
                        owner = "",
                        repo = "",
                        defaultBranch = "",
                        mode = ChatMode.GENERAL,
                        workingBranch = null,
                    ),
                    ciStatus = null,
                    ciFailure = null,
                    autoFixUntilCiGreen = false,
                )
            }
        }
    }

    fun openRepoPicker() {
        _uiState.update { it.copy(repoPickerOpen = true, repoOptionsError = null) }
        if (_uiState.value.repoOptions.isEmpty()) loadRepoOptions()
    }

    fun closeRepoPicker() = _uiState.update { it.copy(repoPickerOpen = false) }

    fun loadRepoOptions() {
        if (_uiState.value.repoOptionsLoading) return
        _uiState.update { it.copy(repoOptionsLoading = true, repoOptionsError = null) }
        viewModelScope.launch {
            try {
                val repos = githubService.listRepos()
                _uiState.update { it.copy(repoOptions = repos, repoOptionsLoading = false) }
            } catch (e: AppError) {
                _uiState.update {
                    it.copy(repoOptionsLoading = false, repoOptionsError = e.userMessage)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        repoOptionsLoading = false,
                        repoOptionsError = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Could not load repositories.",
                    )
                }
            }
        }
    }

    /* ------------------------------ sending ----------------------------- */

    fun setPendingAttachment(attachment: PendingAttachment?) {
        _uiState.update { it.copy(pendingAttachment = attachment) }
    }

    fun clearPendingAttachment() = setPendingAttachment(null)

    fun setAutoFixUntilCiGreen(enabled: Boolean) {
        _uiState.update { it.copy(autoFixUntilCiGreen = enabled) }
    }

    fun send(text: String, attachment: ChatAttachment? = null) =
        sendInternal(text, attachment, resend = false)

    fun retry() {
        turnCoordinator.lastUserInput()?.let {
            sendInternal(
                it,
                turnCoordinator.lastAttachment(),
                resend = true,
                autoFixOverride = turnCoordinator.lastAutoFix(),
            )
        }
    }

    private fun sendInternal(
        text: String,
        attachment: ChatAttachment?,
        resend: Boolean,
        autoFixOverride: Boolean? = null,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachment == null) return
        val session = _uiState.value.session ?: return
        val state = _uiState.value
        if (state.typing || state.approvalPending || state.approving) return
        // A turn may be running for ANOTHER conversation (turns are app-wide).
        // Surface the conflict BEFORE persisting anything — silently dropping
        // the message after append would make it disappear into a void (AUD-002).
        if (turnCoordinator.state.value.active) {
            showSnackbar(R.string.chat_turn_in_progress)
            return
        }
        val repoBound = !session.isGeneral && session.owner.isNotBlank()

        val displayText = buildString {
            if (attachment != null) {
                append("📎 ").append(attachment.displayName)
                if (trimmed.isNotEmpty()) append('\n')
            }
            append(trimmed)
        }.ifBlank { "📎 ${attachment?.displayName.orEmpty()}" }

        val userText = trimmed.ifEmpty { displayText }
        val autoFix = if (repoBound) {
            autoFixOverride ?: state.autoFixUntilCiGreen
        } else {
            false
        }
        turnCoordinator.rememberRetry(userText, attachment, autoFix = autoFix)
        _uiState.update { it.copy(pendingAttachment = null, error = null) }
        ciProbeJob?.cancel()

        viewModelScope.launch {
            if (!resend) {
                val label = if (autoFix) {
                    "🔁 Auto-fix until CI green\n$displayText"
                } else {
                    displayText
                }
                chatRepository.appendUserText(
                    boundKey.ifBlank { session.repoKey },
                    session.sessionId,
                    label,
                )
            }
            val request = TurnRequest(
                repoKey = boundKey.ifBlank { session.repoKey },
                owner = session.owner,
                repo = session.repo,
                defaultBranch = session.defaultBranch,
                workingBranch = session.workingBranch,
                sessionId = session.sessionId,
                userText = userText,
                attachment = attachment,
                mode = if (repoBound) ChatMode.REPO else ChatMode.GENERAL,
                autoFixUntilCiGreen = autoFix,
                preferredConnectionId = _uiState.value.activeProviderId,
            )
            // Runs in the application-scoped coordinator + FGS — not viewModelScope.
            val started = turnCoordinator.startTurn(request)
            if (!started) {
                // Another conversation started a turn between our check and
                // now (sub-millisecond window). Keep the message visible and
                // surface the conflict — retry() reuses it without duplicating.
                showSnackbar(R.string.chat_turn_in_progress)
            }
        }
    }

    fun approveChange() = turnCoordinator.approveChange()

    fun rejectChange() = turnCoordinator.rejectChange()

    fun cancelTurn() {
        ciProbeJob?.cancel()
        turnCoordinator.cancelTurn()
    }

    /* --------------------------- pull requests -------------------------- */

    fun createPullRequestNow() {
        val session = _uiState.value.session ?: return
        if (session.isGeneral) return
        if (_uiState.value.prState == PrState.Creating) return
        val head = session.workingBranch ?: "ai-chat/${session.sessionId}"
        _uiState.update { it.copy(prState = PrState.Creating) }
        viewModelScope.launch {
            val newState = try {
                val info = createPullRequest(
                    owner = session.owner,
                    repo = session.repo,
                    head = head,
                    base = session.defaultBranch,
                    title = "AI changes from $head",
                    body = "Changes proposed by the RepoChat AI on working branch `$head`.\n\n" +
                        "Review and merge when ready — merging is always a manual step.",
                )
                PrState.Ready(info)
            } catch (e: AppError) {
                PrState.Failed(e.userMessage)
            } catch (e: Exception) {
                PrState.Failed(
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not create the pull request.",
                )
            }
            _uiState.update { it.copy(prState = newState) }
        }
    }

    fun dismissPrDialog() {
        turnCoordinator.dismissPrInfo()
        _uiState.update { it.copy(prState = PrState.None) }
    }

    /* ------------------------------ housekeeping ------------------------- */

    fun clearConversation() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            chatRepository.clearMessages(boundKey.ifBlank { session.repoKey }, session.sessionId)
        }
    }

    fun dismissError() {
        turnCoordinator.dismissError()
        _uiState.update { it.copy(error = null) }
    }

    fun consumeTreeTruncated() {
        turnCoordinator.consumeTreeTruncated()
        _uiState.update { it.copy(treeTruncated = false) }
    }

    fun onSnackbarShown() = _uiState.update { it.copy(snackbar = SnackbarEvent()) }

    private fun showSnackbar(textRes: Int, vararg args: Any) {
        snackbarCounter++
        _uiState.update { it.copy(snackbar = SnackbarEvent(snackbarCounter, textRes, args.toList())) }
    }

    /* --------------------------- CI build center ------------------------- */

    /** Fetches the latest Actions run for this conversation's branch. */
    fun refreshCiStatus() {
        val session = _uiState.value.session ?: return
        if (session.isGeneral || session.owner.isBlank()) return
        viewModelScope.launch {
            try {
                val branch = session.workingBranch ?: session.defaultBranch
                val latest = githubService
                    .listWorkflowRuns(session.owner, session.repo, branch)
                    .firstOrNull() ?: return@launch
                _uiState.update { s ->
                    s.copy(
                        ciStatus = latest,
                        ciFailure = if (latest.conclusion == "failure") latest else null,
                    )
                }
            } catch (_: Exception) {
                // Chip stays as-is; the sheet surfaces errors on explicit load.
            }
        }
    }

    /**
     * Watches CI after a single-turn commit (no auto-fix loop): probes at
     * 45s / 2min / 4min. Stops early on a conclusion. Front-ground only —
     * the in-app sheet always offers a manual refresh too.
     */
    private fun scheduleCiProbes() {
        ciProbeJob?.cancel()
        ciProbeJob = viewModelScope.launch {
            for (delayMs in longArrayOf(45_000L, 75_000L, 120_000L)) {
                delay(delayMs)
                if (turnCoordinator.state.value.active) return@launch
                refreshCiStatus()
                val status = _uiState.value.ciStatus ?: return@launch
                if (status.conclusion != null) return@launch
            }
        }
    }

    fun dismissCiFailure() = _uiState.update { it.copy(ciFailure = null) }

    fun openCiSheet() {
        _uiState.update {
            it.copy(
                ciSheetOpen = true,
                ciJobs = emptyList(),
                ciJobsError = null,
                selectedJob = null,
                jobLog = null,
                jobLogError = null,
            )
        }
        loadCiJobs()
    }

    fun closeCiSheet() = _uiState.update { it.copy(ciSheetOpen = false) }

    private fun loadCiJobs() {
        val state = _uiState.value
        val run = state.ciStatus ?: return
        val session = state.session ?: return
        if (state.ciJobsLoading) return
        _uiState.update { it.copy(ciJobsLoading = true, ciJobsError = null) }
        viewModelScope.launch {
            try {
                val jobs = githubService.listJobsForRun(session.owner, session.repo, run.id)
                val focus = jobs.firstOrNull { it.conclusion == "failure" }
                    ?: jobs.firstOrNull { it.conclusion != "success" }
                    ?: jobs.firstOrNull()
                _uiState.update {
                    it.copy(ciJobs = jobs, ciJobsLoading = false, selectedJob = focus)
                }
                focus?.let { job -> loadJobLog(job) }
            } catch (e: AppError) {
                _uiState.update { it.copy(ciJobsLoading = false, ciJobsError = e.userMessage) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        ciJobsLoading = false,
                        ciJobsError = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Could not load build jobs.",
                    )
                }
            }
        }
    }

    fun selectJob(job: WorkflowJobInfo) {
        if (_uiState.value.selectedJob?.id == job.id && _uiState.value.jobLog != null) return
        _uiState.update { it.copy(selectedJob = job, jobLog = null, jobLogError = null) }
        loadJobLog(job)
    }

    private fun loadJobLog(job: WorkflowJobInfo) {
        val state = _uiState.value
        val session = state.session ?: return
        if (state.jobLogLoading) return
        _uiState.update { it.copy(jobLogLoading = true, jobLogError = null) }
        viewModelScope.launch {
            try {
                val raw = githubService.getJobLogs(session.owner, session.repo, job.id)
                val tail = AutoFixLoop.truncateTail(raw, LOG_TAIL_CHARS)
                _uiState.update { it.copy(jobLog = tail, jobLogLoading = false) }
            } catch (e: AppError) {
                _uiState.update { it.copy(jobLogLoading = false, jobLogError = e.userMessage) }
            } catch (_: Exception) {
                // GitHub packages logs asynchronously right after a run ends.
                _uiState.update {
                    it.copy(
                        jobLogLoading = false,
                        jobLogError = "Log is not available yet — GitHub archives it " +
                            "shortly after the run finishes. Try again in a moment.",
                    )
                }
            }
        }
    }

    /**
     * "Fix with AI" from the failure banner / build sheet: pulls the failing
     * job log and hands it to the agent as an attachment, with auto-fix
     * until CI green enabled so the loop keeps watch after the fix commit.
     */
    fun fixWithAi() {
        val state = _uiState.value
        if (state.typing || state.approvalPending || state.approving) return
        val session = state.session ?: return
        val failure = state.ciFailure
            ?: state.ciStatus?.takeIf { it.conclusion == "failure" }
            ?: return
        viewModelScope.launch {
            val logTail = state.jobLog?.takeIf { it.isNotBlank() }
                ?: fetchFailureLogTail(session, failure)
            _uiState.update { it.copy(ciSheetOpen = false, ciFailure = null) }
            setAutoFixUntilCiGreen(true)
            val branch = session.workingBranch ?: session.defaultBranch
            sendInternal(
                text = "The CI build failed on branch `$branch` " +
                    "(workflow: ${failure.name.ifBlank { "build" }}). " +
                    "Find the root cause in the repo, fix it, and commit the fix.",
                attachment = logTail?.let {
                    ChatAttachment(
                        displayName = "ci-failure-log.txt",
                        mimeType = "text/plain",
                        textContent = it,
                    )
                },
                resend = false,
            )
        }
    }

    private suspend fun fetchFailureLogTail(
        session: RepoSession,
        run: WorkflowRunInfo,
    ): String? = try {
        val jobs = githubService.listJobsForRun(session.owner, session.repo, run.id)
        val failed = jobs.firstOrNull { it.conclusion == "failure" }
            ?: jobs.firstOrNull { it.steps.any { s -> s.conclusion == "failure" } }
            ?: jobs.firstOrNull() ?: return null
        val raw = githubService.getJobLogs(session.owner, session.repo, failed.id)
        AutoFixLoop.truncateTail(raw, LOG_TAIL_CHARS)
    } catch (_: Exception) {
        null
    }

    private companion object {
        /** Display/attachment cap for job logs — errors live at the tail. */
        const val LOG_TAIL_CHARS = 16_000
    }
}
