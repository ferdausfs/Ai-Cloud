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
    val detail: String = "",
)

data class SettingsUiState(
    val githubPat: String = "",
    val connections: List<ServiceConnection> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val githubTest: TestState = TestState(),
    val connectionTests: Map<String, TestState> = emptyMap(),
    /** Per-connection: user chose free-text model entry. */
    val customModelIds: Set<String> = emptySet(),
    val modelLists: Map<String, ModelListState> = emptyMap(),
    val activeRepo: ActiveRepo? = null,
    val savedFlash: Boolean = false,
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
                val preset = KNOWN_OPENAI_PROVIDERS.first()
                ServiceConnection(
                    id = id,
                    type = type,
                    label = preset.label,
                    baseUrl = preset.baseUrl,
                    apiKey = "",
                    modelName = suggestedModelsFor(preset.label).firstOrNull().orEmpty(),
                )
            }
            ConnectionType.GITHUB -> return
        }
        _uiState.update {
            it.copy(
                connections = it.connections + draft,
                providerOrder = it.providerOrder + id,
                editingConnectionId = id,
                customModelIds = it.customModelIds - id,
            )
        }
    }

    fun startEditConnection(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id }
        val useCustom = when {
            conn == null -> false
            conn.type == ConnectionType.OLLAMA ->
                conn.modelName.isNotBlank() && conn.modelName !in KNOWN_OLLAMA_CLOUD_MODELS
            else -> {
                val models = suggestedModelsFor(matchOpenAiPreset(conn.baseUrl).label)
                conn.modelName.isNotBlank() && models.isNotEmpty() && conn.modelName !in models
            }
        }
        _uiState.update { state ->
            state.copy(
                editingConnectionId = id,
                customModelIds = if (useCustom) state.customModelIds + id else state.customModelIds - id,
            )
        }
        loadModels(id)
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

    fun selectOpenAiPreset(connectionId: String, presetLabel: String) {
        val preset = KNOWN_OPENAI_PROVIDERS.firstOrNull { it.label == presetLabel } ?: return
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        val models = suggestedModelsFor(preset.label)
        val next = conn.copy(
            label = if (preset.baseUrl.isNotEmpty()) preset.label else conn.label,
            baseUrl = preset.baseUrl,
            modelName = when {
                preset.baseUrl == conn.baseUrl -> conn.modelName
                models.isNotEmpty() -> models.first()
                else -> ""
            },
        )
        updateConnection(next)
        _uiState.update { it.copy(customModelIds = it.customModelIds - connectionId) }
    }

    fun setUseCustomModel(connectionId: String, custom: Boolean) {
        _uiState.update { state ->
            if (custom) {
                state.copy(customModelIds = state.customModelIds + connectionId)
            } else {
                state.copy(customModelIds = state.customModelIds - connectionId)
            }
        }
    }

    fun onApiKeyChanged(connectionId: String, apiKey: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        updateConnection(conn.copy(apiKey = apiKey))
        if (apiKey.trim().length >= 8) {
            loadModels(connectionId, debounceMs = 600)
        }
    }

    fun loadModels(connectionId: String, debounceMs: Long = 0) {
        loadModelsJob?.cancel()
        loadModelsJob = viewModelScope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return@launch
            val curated = when (conn.type) {
                ConnectionType.OLLAMA -> KNOWN_OLLAMA_CLOUD_MODELS
                ConnectionType.OPENAI_COMPATIBLE ->
                    suggestedModelsFor(matchOpenAiPreset(conn.baseUrl).label)
                else -> emptyList()
            }
            _uiState.update {
                val prev = it.modelLists[connectionId] ?: ModelListState()
                it.copy(
                    modelLists = it.modelLists + (
                        connectionId to prev.copy(status = ModelListStatus.Loading)
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
                curated.isNotEmpty() -> curated
                else -> emptyList()
            }
            val failed = live.isEmpty() && curated.isEmpty() &&
                conn.type == ConnectionType.OPENAI_COMPATIBLE
            _uiState.update {
                it.copy(
                    modelLists = it.modelLists + (
                        connectionId to ModelListState(
                            status = if (failed) ModelListStatus.Failed else ModelListStatus.Ready,
                            models = models,
                            detail = if (failed) {
                                "Could not load models — enter the model name manually"
                            } else {
                                ""
                            },
                        )
                        ),
                )
            }
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
                customModelIds = state.customModelIds - id,
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

    companion object {
        /** Curated starter models per known provider (live list can replace later). */
        fun suggestedModelsFor(providerLabel: String): List<String> = when (providerLabel) {
            "Groq" -> listOf(
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "gemma2-9b-it",
                "qwen/qwen3-32b",
            )
            "Cerebras" -> listOf(
                "llama-3.3-70b",
                "llama3.1-8b",
                "gpt-oss-120b",
            )
            "OpenRouter" -> listOf(
                "openai/gpt-4o-mini",
                "google/gemini-2.0-flash-001",
                "meta-llama/llama-3.3-70b-instruct",
            )
            "Together.ai" -> listOf(
                "meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo",
                "meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo",
            )
            "Fireworks" -> listOf(
                "accounts/fireworks/models/llama-v3p1-70b-instruct",
                "accounts/fireworks/models/llama-v3p1-8b-instruct",
            )
            else -> emptyList()
        }
    }
}
