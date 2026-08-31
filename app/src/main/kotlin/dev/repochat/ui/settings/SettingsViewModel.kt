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
import dev.repochat.core.model.KNOWN_OLLAMA_CLOUD_MODELS
import dev.repochat.core.model.KNOWN_OPENAI_PROVIDERS
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.matchOpenAiPreset
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class ModelListStatus { Idle, Loading, Ready, Failed }

data class ModelListState(
    val status: ModelListStatus = ModelListStatus.Idle,
    val models: List<String> = emptyList(),
    /** When true, user chose Custom / free-text entry for the model. */
    val useCustomModel: Boolean = false,
    val detail: String = "",
)

data class SettingsUiState(
    val githubPat: String = "",
    val connections: List<ServiceConnection> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val githubTest: TestState = TestState(),
    val connectionTests: Map<String, TestState> = emptyMap(),
    val modelLists: Map<String, ModelListState> = emptyMap(),
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

    private var loadModelsJob: Job? = null

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
                modelName = KNOWN_OLLAMA_CLOUD_MODELS.first(),
            )
            ConnectionType.OPENAI_COMPATIBLE -> {
                val preset = KNOWN_OPENAI_PROVIDERS.first() // Groq
                ServiceConnection(
                    id = id,
                    type = type,
                    label = preset.label,
                    baseUrl = preset.baseUrl,
                    apiKey = "",
                    modelName = "",
                )
            }
            ConnectionType.GITHUB -> return
        }
        _uiState.update {
            it.copy(
                connections = it.connections + draft,
                providerOrder = it.providerOrder + id,
                editingConnectionId = id,
                modelLists = it.modelLists + (
                    id to ModelListState(
                        useCustomModel = false,
                        models = if (type == ConnectionType.OLLAMA) {
                            KNOWN_OLLAMA_CLOUD_MODELS
                        } else {
                            emptyList()
                        },
                        status = if (type == ConnectionType.OLLAMA) {
                            ModelListStatus.Ready
                        } else {
                            ModelListStatus.Idle
                        },
                    )
                    ),
            )
        }
        if (type == ConnectionType.OLLAMA) {
            // Seed with curated list; try live tags in the background.
            loadModels(id, debounceMs = 0)
        }
    }

    fun startEditConnection(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id }
        _uiState.update { state ->
            val listState = when {
                conn == null -> ModelListState()
                conn.type == ConnectionType.OLLAMA -> ModelListState(
                    status = ModelListStatus.Ready,
                    models = KNOWN_OLLAMA_CLOUD_MODELS,
                    useCustomModel = conn.modelName.isNotBlank() &&
                        conn.modelName !in KNOWN_OLLAMA_CLOUD_MODELS,
                )
                else -> {
                    val preset = matchOpenAiPreset(conn.baseUrl)
                    ModelListState(
                        useCustomModel = conn.modelName.isNotBlank(),
                    )
                }
            }
            state.copy(
                editingConnectionId = id,
                modelLists = state.modelLists + (id to listState),
            )
        }
        if (conn != null) {
            loadModels(id, debounceMs = 0)
        }
    }

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

    /** OpenAI-compatible: pick a known preset (or Custom). */
    fun selectOpenAiPreset(connectionId: String, presetLabel: String) {
        val preset = KNOWN_OPENAI_PROVIDERS.firstOrNull { it.label == presetLabel } ?: return
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        val next = conn.copy(
            label = if (preset.baseUrl.isNotEmpty()) preset.label else conn.label,
            baseUrl = preset.baseUrl,
            // Clear model when switching providers so the new list isn't stale.
            modelName = if (preset.baseUrl != conn.baseUrl) "" else conn.modelName,
        )
        updateConnection(next)
        _uiState.update {
            it.copy(
                modelLists = it.modelLists + (
                    connectionId to ModelListState(status = ModelListStatus.Idle)
                    ),
            )
        }
        if (preset.baseUrl.isNotEmpty() && next.apiKey.isNotBlank()) {
            loadModels(connectionId)
        }
    }

    fun setUseCustomModel(connectionId: String, custom: Boolean) {
        _uiState.update { state ->
            val prev = state.modelLists[connectionId] ?: ModelListState()
            state.copy(
                modelLists = state.modelLists + (
                    connectionId to prev.copy(useCustomModel = custom)
                    ),
            )
        }
    }

    fun onApiKeyChanged(connectionId: String, apiKey: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        updateConnection(conn.copy(apiKey = apiKey))
        if (conn.type == ConnectionType.OPENAI_COMPATIBLE &&
            conn.baseUrl.isNotBlank() &&
            apiKey.trim().length >= 8
        ) {
            loadModels(connectionId, debounceMs = 600)
        } else if (conn.type == ConnectionType.OLLAMA && apiKey.trim().length >= 4) {
            loadModels(connectionId, debounceMs = 600)
        }
    }

    fun loadModels(connectionId: String, debounceMs: Long = 0) {
        loadModelsJob?.cancel()
        loadModelsJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return@launch
            if (conn.type == ConnectionType.OPENAI_COMPATIBLE && conn.baseUrl.isBlank()) {
                _uiState.update {
                    it.copy(
                        modelLists = it.modelLists + (
                            connectionId to ModelListState(
                                status = ModelListStatus.Failed,
                                detail = "Enter a base URL first",
                                useCustomModel = true,
                            )
                            ),
                    )
                }
                return@launch
            }
            _uiState.update {
                val prev = it.modelLists[connectionId] ?: ModelListState()
                it.copy(
                    modelLists = it.modelLists + (
                        connectionId to prev.copy(status = ModelListStatus.Loading, detail = "")
                        ),
                )
            }
            val live = try {
                llm.listModels(conn)
            } catch (_: Exception) {
                emptyList()
            }
            val models = when {
                live.isNotEmpty() -> live
                conn.type == ConnectionType.OLLAMA -> KNOWN_OLLAMA_CLOUD_MODELS
                else -> emptyList()
            }
            val failed = live.isEmpty() && conn.type == ConnectionType.OPENAI_COMPATIBLE
            val prev = _uiState.value.modelLists[connectionId] ?: ModelListState()
            val selectedStillValid = conn.modelName.isNotBlank() && conn.modelName in models
            _uiState.update {
                it.copy(
                    modelLists = it.modelLists + (
                        connectionId to ModelListState(
                            status = if (failed) ModelListStatus.Failed else ModelListStatus.Ready,
                            models = models,
                            useCustomModel = when {
                                failed -> true
                                prev.useCustomModel && !selectedStillValid && conn.modelName.isNotBlank() -> true
                                models.isEmpty() -> true
                                else -> prev.useCustomModel && conn.modelName !in models
                            },
                            detail = if (failed) {
                                "Couldn't load the model list — enter the model name manually"
                            } else {
                                ""
                            },
                        )
                        ),
                )
            }
            // If we got a list and nothing selected yet, pick the first model.
            if (models.isNotEmpty() && conn.modelName.isBlank()) {
                updateConnection(conn.copy(modelName = models.first()))
            }
        }
    }

    fun deleteConnection(id: String) {
        _uiState.update { state ->
            state.copy(
                connections = state.connections.filterNot { it.id == id },
                providerOrder = state.providerOrder.filterNot { it == id },
                activeProviderId = state.activeProviderId?.takeIf { it != id },
                editingConnectionId = null,
                modelLists = state.modelLists - id,
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
