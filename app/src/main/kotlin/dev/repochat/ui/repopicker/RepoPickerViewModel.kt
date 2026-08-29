package dev.repochat.ui.repopicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.ListReposUseCase
import dev.repochat.core.domain.SetActiveRepoUseCase
import dev.repochat.core.model.AppError
import dev.repochat.core.model.RepoSummary
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RepoPickerUiState(
    val repos: List<RepoSummary> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val error: AppError? = null,
)

@HiltViewModel
class RepoPickerViewModel @Inject constructor(
    private val listRepos: ListReposUseCase,
    private val setActiveRepo: SetActiveRepoUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RepoPickerUiState())
    val uiState: StateFlow<RepoPickerUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onQueryChange(value: String) = _uiState.update { it.copy(query = value) }

    fun refresh() {
        _uiState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val repos = listRepos()
                _uiState.update { it.copy(loading = false, repos = repos) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                _uiState.update { it.copy(loading = false, error = e) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        error = AppError.Network(
                            e.message?.takeIf { it.isNotBlank() }
                                ?: "Failed to load repositories",
                        ),
                    )
                }
            }
        }
    }

    fun select(repo: RepoSummary) {
        viewModelScope.launch {
            setActiveRepo(repo)
        }
    }
}
