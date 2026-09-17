package dev.repochat.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.repochat.core.domain.LlmService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ProviderPreset
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.matchOpenAiPreset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Persists whether the first-run onboarding has been completed (or skipped).
 * A plain flag in SharedPreferences — nothing secret here.
 */
@Singleton
class FirstRunController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_DONE, false))
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun complete() {
        _completed.value = true
        prefs.edit().putBoolean(KEY_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "ui_prefs"
        const val KEY_DONE = "onboarding_done"
    }
}

/** Curated first-run provider choices + sensible starter models (pure, testable). */
object OnboardingPresets {

    /** Short, high-signal list for the first-run picker (no templates). */
    fun curated(): List<ProviderPreset> = listOf(
        "Groq",
        "OpenRouter",
        "Google Gemini",
        "Cerebras",
        "GitHub Models",
        "Custom",
    ).mapNotNull { label -> ALL.filter { it.label == label }.firstOrNull() }

    private val ALL: List<ProviderPreset> = dev.repochat.core.model.KNOWN_OPENAI_PROVIDERS

    fun preset(label: String): ProviderPreset =
        ALL.firstOrNull { it.label == label } ?: ProviderPreset("Custom", "")

    /** Sensible starter model per preset (user-editable; live list available in Settings). */
    fun suggestedModel(label: String): String = when (label) {
        "Groq" -> "llama-3.3-70b-versatile"
        "OpenRouter" -> "meta-llama/llama-3.3-70b-instruct:free"
        "Google Gemini" -> "gemini-2.0-flash"
        "Cerebras" -> "llama-3.3-70b"
        "GitHub Models" -> "openai/gpt-4o-mini"
        else -> ""
    }
}

data class OnboardingUiState(
    val step: Int = 0,
    val presetLabel: String = "Groq",
    val baseUrl: String = OnboardingPresets.preset("Groq").baseUrl,
    val apiKey: String = "",
    val modelName: String = OnboardingPresets.suggestedModel("Groq"),
    val testStatus: TestStatus = TestStatus.IDLE,
    val testDetail: String = "",
    val saving: Boolean = false,
) {
    val canFinish: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() &&
            modelName.isNotBlank() && !saving
}

enum class TestStatus { IDLE, TESTING, SUCCESS, FAILED }

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val firstRun: FirstRunController,
    private val llm: LlmService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun nextStep() = _uiState.update { it.copy(step = (it.step + 1).coerceAtMost(2)) }
    fun previousStep() = _uiState.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun selectPreset(label: String) {
        val preset = OnboardingPresets.preset(label)
        _uiState.update {
            it.copy(
                presetLabel = label,
                baseUrl = preset.baseUrl,
                modelName = OnboardingPresets.suggestedModel(label),
                testStatus = TestStatus.IDLE,
                testDetail = "",
            )
        }
    }

    fun onBaseUrlChange(value: String) =
        _uiState.update { it.copy(baseUrl = value.trim(), testStatus = TestStatus.IDLE) }

    fun onApiKeyChange(value: String) =
        _uiState.update { it.copy(apiKey = value.trim(), testStatus = TestStatus.IDLE) }

    fun onModelChange(value: String) =
        _uiState.update { it.copy(modelName = value.trim(), testStatus = TestStatus.IDLE) }

    /** Live GET /models probe — costs no tokens, gives precise key/endpoint errors. */
    fun testConnection() {
        val s = _uiState.value
        if (s.testStatus == TestStatus.TESTING) return
        _uiState.update { it.copy(testStatus = TestStatus.TESTING, testDetail = "") }
        viewModelScope.launch {
            val conn = buildConnection(s)
            try {
                val detail = llm.test(conn)
                _uiState.update {
                    it.copy(testStatus = TestStatus.SUCCESS, testDetail = detail)
                }
            } catch (e: dev.repochat.core.model.AppError) {
                _uiState.update {
                    it.copy(testStatus = TestStatus.FAILED, testDetail = e.userMessage)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        testStatus = TestStatus.FAILED,
                        testDetail = e.message?.takeIf { m -> m.isNotBlank() }
                            ?: "Connection failed — check the endpoint and key",
                    )
                }
            }
        }
    }

    /**
     * Saves the provider as a first-class connection (replaces the LLM list —
     * onboarding implies a fresh install) and marks onboarding complete.
     */
    fun finish(onDone: () -> Unit) {
        val s = _uiState.value
        if (!s.canFinish) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val current = settingsRepository.current()
                val conn = buildConnection(s)
                val existingLlm = current.connections.filter {
                    it.type == ConnectionType.OLLAMA || it.type == ConnectionType.OPENAI_COMPATIBLE
                }
                settingsRepository.save(
                    current.copy(
                        connections = current.connections - existingLlm.toSet() + conn,
                        providerOrder = listOf(conn.id),
                        activeProviderId = conn.id,
                    ),
                )
            } catch (_: Exception) {
                // Even if persisting fails, never trap the user in onboarding.
            }
            firstRun.complete()
            _uiState.update { it.copy(saving = false) }
            onDone()
        }
    }

    fun skip() = firstRun.complete()

    private fun buildConnection(s: OnboardingUiState): ServiceConnection {
        val preset = OnboardingPresets.preset(s.presetLabel)
        val effectivePreset = matchOpenAiPreset(s.baseUrl.trim().trimEnd('/'))
        return ServiceConnection(
            id = "onboard-${UUID.randomUUID()}",
            type = ConnectionType.OPENAI_COMPATIBLE,
            label = effectivePreset.label.ifBlank { preset.label },
            baseUrl = s.baseUrl.trim().trimEnd('/'),
            apiKey = s.apiKey.trim(),
            modelName = s.modelName.trim(),
        )
    }
}
