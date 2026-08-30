package dev.repochat.ui.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.GithubService
import dev.repochat.core.domain.SetActiveRepoUseCase
import dev.repochat.core.model.AppError
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.RepoSummary
import dev.repochat.core.model.TreeEntry
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepoDetailUiState(
    val loading: Boolean = true,
    val error: AppError? = null,
    val entries: List<TreeEntry> = emptyList(),
    val truncated: Boolean = false,
    /** Path currently open in the file viewer, or null. */
    val openPath: String? = null,
    val openContent: String? = null,
    val openLoading: Boolean = false,
    val openError: String? = null,
    val openBinary: Boolean = false,
)

@HiltViewModel
class RepoDetailViewModel @Inject constructor(
    private val github: GithubService,
    private val setActiveRepo: SetActiveRepoUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepoDetailUiState())
    val uiState: StateFlow<RepoDetailUiState> = _uiState.asStateFlow()

    private var owner: String = ""
    private var repo: String = ""
    private var branch: String = ""

    fun start(owner: String, repo: String, defaultBranch: String) {
        if (this.owner == owner && this.repo == repo && this.branch == defaultBranch &&
            _uiState.value.entries.isNotEmpty()
        ) {
            return
        }
        this.owner = owner
        this.repo = repo
        this.branch = defaultBranch
        loadTree()
    }

    fun refresh() = loadTree()

    /** Marks this repo as the last-active one before opening chat. */
    fun prepareChat(onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                setActiveRepo(
                    RepoSummary(
                        id = 0L,
                        name = repo,
                        fullName = "$owner/$repo",
                        isPrivate = false,
                        description = null,
                        language = null,
                        updatedAtMillis = null,
                        defaultBranch = branch,
                        htmlUrl = "",
                    ),
                )
            } catch (_: Exception) {
                // Non-fatal — chat still opens.
            }
            onReady()
        }
    }

    fun openFile(path: String) {
        if (path.isBlank()) return
        _uiState.update {
            it.copy(
                openPath = path,
                openContent = null,
                openLoading = true,
                openError = null,
                openBinary = false,
            )
        }
        viewModelScope.launch {
            try {
                val file: GitFile? = github.fileContent(owner, repo, path, branch)
                when {
                    file == null -> _uiState.update {
                        it.copy(openLoading = false, openError = "File not found on $branch")
                    }
                    file.isBinary -> _uiState.update {
                        it.copy(openLoading = false, openBinary = true, openContent = null)
                    }
                    else -> _uiState.update {
                        it.copy(openLoading = false, openContent = file.content, openBinary = false)
                    }
                }
            } catch (e: AppError) {
                _uiState.update { it.copy(openLoading = false, openError = e.userMessage) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        openLoading = false,
                        openError = e.message?.takeIf { m -> m.isNotBlank() } ?: "Could not load file",
                    )
                }
            }
        }
    }

    fun closeFile() {
        _uiState.update {
            it.copy(
                openPath = null,
                openContent = null,
                openLoading = false,
                openError = null,
                openBinary = false,
            )
        }
    }

    private fun loadTree() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val tree = github.fileTree(owner, repo, branch)
                _uiState.update {
                    it.copy(
                        loading = false,
                        entries = tree.entries.sortedBy { e -> e.path.lowercase() },
                        truncated = tree.truncated,
                        error = null,
                    )
                }
            } catch (e: AppError) {
                _uiState.update { it.copy(loading = false, error = e) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = AppError.Network(
                            e.message?.takeIf { m -> m.isNotBlank() } ?: "Could not load file tree",
                        ),
                    )
                }
            }
        }
    }
}
