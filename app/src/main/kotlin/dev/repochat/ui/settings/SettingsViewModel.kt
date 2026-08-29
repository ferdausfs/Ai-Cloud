package dev.repochat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.ActiveRepoRepository
import dev.repochat.core.domain.SaveSettingsUseCase
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.domain.TestGithubUseCase
import dev.repochat.core.domain.TestOllamaUseCase
import dev.repochat.core.model.ActiveRepo
import dev.repochat.core.model.AppSettings
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TestStatus { Idle, Testing, Success, Failure }

data class TestState(
    val status: TestStatus = TestStatus.Idle,
    val detail: String = "",
)

data class SettingsUiState(
    val ollamaKey: String = "",
    val modelName: String = "",
    val githubPat: String = "",
    val ollamaTest: TestState = TestState(),
    val githubTest: TestState = TestState(),
    val activeRepo: ActiveRepo? = null,
    val savedFlash: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val activeRepoRepository: ActiveRepoRepository,
    private val saveSettings: SaveSettingsUseCase,
    private val testOllama: TestOllamaUseCase,
    private val testGithub: TestGithubUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        ollamaKey = settings.ollamaKey,
                        modelName = settings.modelName,
                        githubPat = settings.githubPat,
                    )
                }
            }
        }
        viewModelScope.launch {
            activeRepoRepository.active.collect { repo ->
                _uiState.update { it.copy(activeRepo = repo) }
            }
        }
    }

    fun onOllamaKeyChange(value: String) = _uiState.update { it.copy(ollamaKey = value) }

    fun onModelNameChange(value: String) = _uiState.update { it.copy(modelName = value) }

    fun onGithubPatChange(value: String) = _uiState.update { it.copy(githubPat = value) }

    fun save() {
        viewModelScope.launch {
            saveSettings(currentFields())
            _uiState.update { it.copy(savedFlash = true) }
        }
    }

    fun onSavedFlashShown() = _uiState.update { it.copy(savedFlash = false) }

    /**
     * Tests use the values currently in the form: they are saved first so
     * "Test connection" always validates exactly what the user typed.
     */
    fun testOllamaConnection() {
        if (_uiState.value.ollamaTest.status == TestStatus.Testing) return
        _uiState.update { it.copy(ollamaTest = TestState(TestStatus.Testing)) }
        viewModelScope.launch {
            saveSettings(currentFields())
            val result = testOllama()
            _uiState.update {
                it.copy(
                    ollamaTest = TestState(
                        status = if (result.ok) TestStatus.Success else TestStatus.Failure,
                        detail = result.detail,
                    )
                )
            }
        }
    }

    fun testGithubConnection() {
        if (_uiState.value.githubTest.status == TestStatus.Testing) return
        _uiState.update { it.copy(githubTest = TestState(TestStatus.Testing)) }
        viewModelScope.launch {
            saveSettings(currentFields())
            val result = testGithub()
            _uiState.update {
                it.copy(
                    githubTest = TestState(
                        status = if (result.ok) TestStatus.Success else TestStatus.Failure,
                        detail = result.detail,
                    )
                )
            }
        }
    }

    private fun currentFields(): AppSettings = AppSettings(
        ollamaKey = _uiState.value.ollamaKey,
        modelName = _uiState.value.modelName,
        githubPat = _uiState.value.githubPat,
    )
}
