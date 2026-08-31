package dev.repochat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.ActiveRepoRepository
import dev.repochat.core.domain.LlmService
import dev.repochat.core.domain.SaveSettingsUseCase
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.domain.TestGithubUseCase
import dev.repochat.core.model.ActiveRepo
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import java.util.UUID
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
    val githubPat: String = "",
    val connections: List<ServiceConnection> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val githubTest: TestState = TestState(),
    val connectionTests: Map<String, TestState> = emptyMap(),
    val activeRepo: ActiveRepo? = null,
    val savedFlash: Boolean = false,
    /** null = list mode; non-null = editing that id (or "new"). */
    val editingConnectionId: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val activeRepoRepository: ActiveRepoRepository,
    private val saveSettings: SaveSettingsUseCase,
    private val llm: LlmService,
    private val testGithub: TestGithubUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        githubPat = settings.githubPat,
                        connections = settings.connections,
                        providerOrder = settings.providerOrder.ifEmpty {
                            settings.llmConnectionsOrdered().map { c -> c.id }
                        },
                        activeProviderId = settings.activeProviderId,
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

    fun onGithubPatChange(value: String) = _uiState.update { it.copy(githubPat = value) }

    fun save() {
        viewModelScope.launch {
            persist()
            _uiState.update { it.copy(savedFlash = true) }
        }
    }

    fun onSavedFlashShown() = _uiState.update { it.copy(savedFlash = false) }

    fun startAddConnection(type: ConnectionType) {
        val id = "new-${UUID.randomUUID()}"
        val draft = when (type) {
            ConnectionType.OLLAMA -> ServiceConnection(
                id = id,
                type = type,
                label = "Ollama",
                baseUrl = "https://ollama.com",
                apiKey = "",
                modelName = "gpt-oss:120b-cloud",
            )
            ConnectionType.OPENAI_COMPATIBLE -> ServiceConnection(
                id = id,
                type = type,
                label = "OpenAI-compatible",
                baseUrl = "https://api.groq.com/openai/v1",
                apiKey = "",
                modelName = "llama-3.3-70b-versatile",
            )
            ConnectionType.GITHUB -> return
        }
        _uiState.update {
            it.copy(
                connections = it.connections + draft,
                providerOrder = it.providerOrder + id,
                editingConnectionId = id,
            )
        }
    }

    fun startEditConnection(id: String) =
        _uiState.update { it.copy(editingConnectionId = id) }

    fun cancelEdit() = _uiState.update { it.copy(editingConnectionId = null) }

    fun updateConnection(updated: ServiceConnection) {
        _uiState.update { state ->
            state.copy(
                connections = state.connections.map {
                    if (it.id == updated.id) updated else it
                },
            )
        }
    }

    fun deleteConnection(id: String) {
        _uiState.update { state ->
            state.copy(
                connections = state.connections.filterNot { it.id == id },
                providerOrder = state.providerOrder.filterNot { it == id },
                activeProviderId = state.activeProviderId?.takeIf { it != id },
                editingConnectionId = null,
            )
        }
        viewModelScope.launch { persist() }
    }

    fun moveProvider(id: String, up: Boolean) {
        _uiState.update { state ->
            val order = state.providerOrder.toMutableList()
            val idx = order.indexOf(id)
            if (idx < 0) return@update state
            val swapWith = if (up) idx - 1 else idx + 1
            if (swapWith !in order.indices) return@update state
            val tmp = order[idx]
            order[idx] = order[swapWith]
            order[swapWith] = tmp
            state.copy(providerOrder = order)
        }
        viewModelScope.launch { persist() }
    }

    fun setActiveProvider(id: String?) {
        _uiState.update { it.copy(activeProviderId = id) }
        viewModelScope.launch { persist() }
    }

    fun testConnection(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id } ?: return
        if (_uiState.value.connectionTests[id]?.status == TestStatus.Testing) return
        _uiState.update {
            it.copy(connectionTests = it.connectionTests + (id to TestState(TestStatus.Testing)))
        }
        viewModelScope.launch {
            persist()
            val result = try {
                val detail = llm.test(conn)
                true to "OK — $detail"
            } catch (e: Exception) {
                false to (e.message?.takeIf { it.isNotBlank() } ?: "Connection failed")
            }
            _uiState.update {
                it.copy(
                    connectionTests = it.connectionTests + (
                        id to TestState(
                            status = if (result.first) TestStatus.Success else TestStatus.Failure,
                            detail = result.second,
                        )
                        ),
                )
            }
        }
    }

    fun testGithubConnection() {
        if (_uiState.value.githubTest.status == TestStatus.Testing) return
        _uiState.update { it.copy(githubTest = TestState(TestStatus.Testing)) }
        viewModelScope.launch {
            persist()
            val result = testGithub()
            _uiState.update {
                it.copy(
                    githubTest = TestState(
                        status = if (result.ok) TestStatus.Success else TestStatus.Failure,
                        detail = result.detail,
                    ),
                )
            }
        }
    }

    fun saveConnectionEdit() {
        viewModelScope.launch {
            persist()
            _uiState.update { it.copy(editingConnectionId = null, savedFlash = true) }
        }
    }

    private suspend fun persist() {
        val s = _uiState.value
        val primaryOllama = s.connections.firstOrNull { it.type == ConnectionType.OLLAMA }
        saveSettings(
            AppSettings(
                ollamaKey = primaryOllama?.apiKey.orEmpty(),
                modelName = primaryOllama?.modelName.orEmpty(),
                githubPat = s.githubPat,
                connections = s.connections,
                providerOrder = s.providerOrder,
                activeProviderId = s.activeProviderId,
            ),
        )
    }
}
