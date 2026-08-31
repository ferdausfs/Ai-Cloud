package dev.repochat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.repochat.core.domain.ActiveRepoRepository
import dev.repochat.core.domain.ExternalServices
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
    /** Legacy flat GitHub PAT — kept in sync with the GITHUB connection. */
    val githubPat: String = "",
    /** Legacy flat GitHub test — only used when no GITHUB connection exists yet. */
    val githubTest: TestState = TestState(),
    val connections: List<ServiceConnection> = emptyList(),
    val providerOrder: List<String> = emptyList(),
    val activeProviderId: String? = null,
    val connectionTests: Map<String, TestState> = emptyMap(),
    /** Per-connection: user chose free-text model entry. */
    val customModelIds: Set<String> = emptySet(),
    /** Per-connection: when true, model picker hides non-:free ids (default ON for OpenRouter). */
    val freeOnlyByConnection: Map<String, Boolean> = emptyMap(),
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
    private val external: ExternalServices,
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
        if (type == ConnectionType.GITHUB && _uiState.value.connections.any { it.type == ConnectionType.GITHUB }) {
            // One GitHub credential row is enough — edit the existing one.
            val existing = _uiState.value.connections.first { it.type == ConnectionType.GITHUB }
            _uiState.update { it.copy(editingConnectionId = existing.id) }
            return
        }
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
            ConnectionType.GITHUB -> ServiceConnection(
                id = id,
                type = type,
                label = "GitHub",
                baseUrl = "https://api.github.com",
                apiKey = _uiState.value.githubPat,
            )
            ConnectionType.CLOUDFLARE -> ServiceConnection(
                id = id,
                type = type,
                label = "Cloudflare",
                baseUrl = "https://api.cloudflare.com/client/v4",
            )
            ConnectionType.VERCEL -> ServiceConnection(
                id = id,
                type = type,
                label = "Vercel",
                baseUrl = "https://api.vercel.com",
            )
            ConnectionType.FIREBASE -> ServiceConnection(
                id = id,
                type = type,
                label = "Firebase",
                baseUrl = "https://firebase.googleapis.com/v1beta1",
            )
        }
        _uiState.update {
            it.copy(
                connections = it.connections + draft,
                providerOrder = it.providerOrder + id,
                editingConnectionId = id,
                customModelIds = it.customModelIds - id,
                freeOnlyByConnection = it.freeOnlyByConnection + (
                    id to defaultFreeOnlyForConnection(draft)
                    ),
            )
        }
        if (type == ConnectionType.OLLAMA || type == ConnectionType.OPENAI_COMPATIBLE) {
            loadModels(id)
        }
    }

    fun startEditConnection(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id }
        val useCustom = when {
            conn == null -> false
            conn.type == ConnectionType.OLLAMA ->
                conn.modelName.isNotBlank() && conn.modelName !in KNOWN_OLLAMA_CLOUD_MODELS
            conn.type == ConnectionType.OPENAI_COMPATIBLE -> {
                val models = suggestedModelsFor(matchOpenAiPreset(conn.baseUrl).label)
                conn.modelName.isNotBlank() && models.isNotEmpty() && conn.modelName !in models
            }
            else -> false
        }
        _uiState.update { state ->
            val freeOnly = state.freeOnlyByConnection[id]
                ?: (conn?.let { defaultFreeOnlyForConnection(it) } ?: false)
            state.copy(
                editingConnectionId = id,
                customModelIds = if (useCustom) state.customModelIds + id else state.customModelIds - id,
                freeOnlyByConnection = state.freeOnlyByConnection + (id to freeOnly),
            )
        }
        if (conn?.type == ConnectionType.OLLAMA || conn?.type == ConnectionType.OPENAI_COMPATIBLE) {
            loadModels(id)
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

    fun selectOpenAiPreset(connectionId: String, presetLabel: String) {
        val preset = KNOWN_OPENAI_PROVIDERS.firstOrNull { it.label == presetLabel } ?: return
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        val models = suggestedModelsFor(preset.label)
        val next = conn.copy(
            label = if (preset.baseUrl.isNotEmpty()) preset.label else conn.label,
            baseUrl = preset.baseUrl,
            modelName = when {
                preset.baseUrl == conn.baseUrl -> conn.modelName
                models.isNotEmpty() && preset.label == "OpenRouter" ->
                    models.firstOrNull { isFreeModelId(it) } ?: models.first()
                models.isNotEmpty() -> models.first()
                else -> ""
            },
        )
        updateConnection(next)
        _uiState.update {
            it.copy(
                customModelIds = it.customModelIds - connectionId,
                freeOnlyByConnection = it.freeOnlyByConnection + (
                    connectionId to defaultFreeOnlyForConnection(next)
                    ),
            )
        }
        if (next.apiKey.isNotBlank()) loadModels(connectionId)
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

    fun setFreeOnly(connectionId: String, freeOnly: Boolean) {
        _uiState.update { state ->
            state.copy(
                freeOnlyByConnection = state.freeOnlyByConnection + (connectionId to freeOnly),
            )
        }
        // If the current selection is paid and free-only is on, pick first free model.
        if (freeOnly) {
            val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
            if (!isFreeModelId(conn.modelName)) {
                val models = _uiState.value.modelLists[connectionId]?.models.orEmpty()
                val free = models.firstOrNull { isFreeModelId(it) }
                    ?: suggestedModelsFor(matchOpenAiPreset(conn.baseUrl).label)
                        .firstOrNull { isFreeModelId(it) }
                if (free != null) {
                    updateConnection(conn.copy(modelName = free))
                }
            }
        }
    }

    fun onApiKeyChanged(connectionId: String, apiKey: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        updateConnection(conn.copy(apiKey = apiKey))
        if (conn.type == ConnectionType.OLLAMA || conn.type == ConnectionType.OPENAI_COMPATIBLE) {
            if (apiKey.trim().length >= 8) {
                loadModels(connectionId, debounceMs = 600)
            }
        }
    }

    fun loadModels(connectionId: String, debounceMs: Long = 0) {
        val conn = _uiState.value.connections.firstOrNull { it.id == connectionId } ?: return
        if (conn.type != ConnectionType.OLLAMA && conn.type != ConnectionType.OPENAI_COMPATIBLE) return
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
            val models = sortModelsFreeFirst(
                when {
                    live.isNotEmpty() -> live
                    curated.isNotEmpty() -> curated
                    else -> emptyList()
                },
            )
            val failed = live.isEmpty() && curated.isEmpty() &&
                conn.type == ConnectionType.OPENAI_COMPATIBLE
            // Default free-only when any :free id is present (or OpenRouter preset).
            val freeOnlyDefault = _uiState.value.freeOnlyByConnection[connectionId]
                ?: (models.any { isFreeModelId(it) } || defaultFreeOnlyForConnection(conn))
            _uiState.update {
                it.copy(
                    modelLists = it.modelLists + (
                        connectionId to ModelListState(
                            status = if (failed) ModelListStatus.Failed else ModelListStatus.Ready,
                            models = models,
                            detail = if (failed) {
                                "Could not load models - enter the model name manually"
                            } else {
                                ""
                            },
                        )
                        ),
                    freeOnlyByConnection = it.freeOnlyByConnection + (connectionId to freeOnlyDefault),
                )
            }
            val freeOnly = freeOnlyDefault
            val preferred = when {
                freeOnly -> models.firstOrNull { isFreeModelId(it) } ?: models.firstOrNull()
                else -> models.firstOrNull()
            }
            if (models.isNotEmpty()) {
                val current = conn.modelName
                val currentOk = current.isNotBlank() && current in models &&
                    (!freeOnly || isFreeModelId(current))
                if (!currentOk && preferred != null) {
                    updateConnection(conn.copy(modelName = preferred))
                }
            }
        }
    }

    fun deleteConnection(id: String) {
        val wasGithub = _uiState.value.connections.any { it.id == id && it.type == ConnectionType.GITHUB }
        _uiState.update { state ->
            state.copy(
                connections = state.connections.filterNot { it.id == id },
                providerOrder = state.providerOrder.filterNot { it == id },
                activeProviderId = state.activeProviderId?.takeIf { it != id },
                editingConnectionId = null,
                customModelIds = state.customModelIds - id,
                // Clear the legacy flat PAT so deleting GitHub does not get
                // resurrected by the store migration on the next launch.
                githubPat = if (wasGithub) "" else state.githubPat,
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

    /** Connection-wide Test Connection: LLM vs GitHub vs external services. */
    fun testConnection(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id } ?: return
        if (_uiState.value.connectionTests[id]?.status == TestStatus.Testing) return
        _uiState.update {
            it.copy(connectionTests = it.connectionTests + (id to TestState(TestStatus.Testing)))
        }
        viewModelScope.launch {
            val result = when {
                conn.type == ConnectionType.GITHUB -> {
                    persist()
                    val r = testGithub()
                    r.ok to r.detail
                }
                conn.isLlm -> {
                    persist()
                    try {
                        true to ("OK — " + llm.test(conn))
                    } catch (e: Exception) {
                        false to (e.message?.takeIf { it.isNotBlank() } ?: "Connection failed")
                    }
                }
                else -> {
                    persist()
                    try {
                        val detail = external.test(conn)
                        true to detail
                    } catch (e: Exception) {
                        false to (e.message?.takeIf { it.isNotBlank() } ?: "Connection failed")
                    }
                }
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

    /** Vercel write: trigger a new deployment against the configured project. */
    fun triggerVercelDeployment(id: String) {
        val conn = _uiState.value.connections.firstOrNull { it.id == id } ?: return
        if (conn.type != ConnectionType.VERCEL) return
        val project = conn.projectId.trim()
        if (project.isBlank()) return
        if (_uiState.value.connectionTests[id]?.status == TestStatus.Testing) return
        _uiState.update {
            it.copy(connectionTests = it.connectionTests + (id to TestState(TestStatus.Testing)))
        }
        viewModelScope.launch {
            persist()
            try {
                val detail = external.triggerDeployment(conn, project)
                _uiState.update {
                    it.copy(
                        connectionTests = it.connectionTests + (
                            id to TestState(TestStatus.Success, detail)
                            ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        connectionTests = it.connectionTests + (
                            id to TestState(
                                TestStatus.Failure,
                                e.message?.takeIf { it.isNotBlank() } ?: "Deployment failed",
                            )
                            ),
                    )
                }
            }
        }
    }

    /** Backwards-compatible entry for the legacy flat form (kept for callers). */
    fun testGithubConnection() {
        val github = _uiState.value.connections.firstOrNull { it.type == ConnectionType.GITHUB }
        if (github != null) {
            testConnection(github.id)
        } else {
            if (_uiState.value.githubTest?.status == TestStatus.Testing) return
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
        val primaryGithub = s.connections.firstOrNull { it.type == ConnectionType.GITHUB }
        saveSettings(
            AppSettings(
                ollamaKey = primaryOllama?.apiKey.orEmpty(),
                modelName = primaryOllama?.modelName.orEmpty(),
                // If the user deleted the GitHub row, clear the legacy flat PAT
                // instead of re-migrating it back on the next launch; the flat
                // value is only a mirror of any GITHUB connection that exists.
                githubPat = primaryGithub?.apiKey.orEmpty(),
                connections = s.connections,
                providerOrder = s.providerOrder,
                activeProviderId = s.activeProviderId,
            ),
        )
    }

    companion object {
        /** OpenRouter (and similar) mark zero-cost routes with a `:free` suffix. */
        fun isFreeModelId(id: String): Boolean =
            id.trim().lowercase().endsWith(":free")

        /** Free models first (stable alpha within each group). */
        fun sortModelsFreeFirst(models: List<String>): List<String> {
            val free = models.filter { isFreeModelId(it) }.sorted()
            val paid = models.filterNot { isFreeModelId(it) }.sorted()
            return free + paid
        }

        fun defaultFreeOnlyForConnection(conn: ServiceConnection): Boolean {
            if (conn.type != ConnectionType.OPENAI_COMPATIBLE) return false
            return matchOpenAiPreset(conn.baseUrl).label == "OpenRouter"
        }

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
                "meta-llama/llama-3.1-8b-instruct:free",
                "google/gemma-2-9b-it:free",
                "qwen/qwen-2.5-7b-instruct:free",
                "meta-llama/llama-3.3-70b-instruct:free",
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
