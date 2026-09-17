package dev.repochat.core.data.repository

import dev.repochat.core.domain.LlmService
import dev.repochat.core.domain.OllamaService
import dev.repochat.core.domain.SettingsRepository
import dev.repochat.core.domain.UsageRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.GeneratedMedia
import dev.repochat.core.model.LlmChatResult
import dev.repochat.core.model.LlmUsage
import dev.repochat.core.model.ModelCapability
import dev.repochat.core.model.ModelCapabilities
import dev.repochat.core.model.OllamaMessage
import dev.repochat.core.model.ServiceConnection
import dev.repochat.core.model.TokenEstimator
import dev.repochat.core.model.UsageClock
import dev.repochat.core.model.UsageEvent
import dev.repochat.core.model.UsageKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tries LLM connections in [AppSettings.providerOrder] order. On rate-limit
 * (HTTP 429 / [AppError.RateLimited]) advances to the next provider.
 */
@Singleton
class LlmRouterImpl @Inject constructor(
    private val settings: SettingsRepository,
    private val ollama: OllamaService,
    private val openAi: OpenAiCompatibleRepositoryImpl,
    private val usageRepo: UsageRepository,
) : LlmService {

    override suspend fun chat(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String?,
    ): LlmChatResult {
        val snap = settings.current()
        enforceDailyBudget(snap)
        val ordered = snap.llmConnectionsOrdered()
        if (ordered.isEmpty()) {
            // Legacy single-key path: synthesize an Ollama connection from flat fields.
            val legacyKey = snap.ollamaKey.trim()
            val legacyModel = snap.modelName.trim()
            if (legacyKey.isBlank() || legacyModel.isBlank()) {
                throw AppError.Configuration(
                    "No AI provider configured. Add Ollama or an OpenAI-compatible connection in Settings.",
                )
            }
            val text = ollama.chat(legacyModel, messages, jsonMode = jsonMode, apiKeyOverride = legacyKey)
            return finishLegacy("legacy-ollama", "Ollama", legacyModel, messages, text)
        }

        val preferred = preferredConnectionId?.let { id -> ordered.firstOrNull { it.id == id } }
            ?: snap.activeProviderId?.let { id -> ordered.firstOrNull { it.id == id } }

        val queue = buildList {
            preferred?.let { add(it) }
            ordered.filter { it.id != preferred?.id }.forEach { add(it) }
        }

        var lastError: AppError? = null
        var fellBackFrom: String? = null
        var firstLabel: String? = null

        for ((index, conn) in queue.withIndex()) {
            if (index == 0) firstLabel = conn.label
            try {
                var captured: LlmUsage? = null
                val text = invokeOne(conn, messages, jsonMode) { captured = it }
                val noteFrom = if (index > 0) firstLabel else null
                val usage = captured ?: TokenEstimator.estimateMessages(messages, text)
                recordUsage(conn, UsageKind.CHAT, usage)
                return LlmChatResult(
                    text = text,
                    connectionId = conn.id,
                    providerLabel = conn.label.ifBlank { conn.type.name },
                    fellBackFrom = noteFrom ?: fellBackFrom,
                    usage = usage,
                )
            } catch (e: AppError.RateLimited) {
                lastError = e
                if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                continue
            } catch (e: AppError) {
                // Non-rate-limit on preferred/first: still try next only for rate limits.
                // For other errors on non-last, keep going only if rate-limit-like message.
                if (isRateLimitLike(e) && index < queue.lastIndex) {
                    lastError = e
                    if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                    continue
                }
                throw e
            }
        }
        throw lastError ?: AppError.Configuration("Every configured AI provider failed.")
    }

    override suspend fun chatStreaming(
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        preferredConnectionId: String?,
        onDelta: (String) -> Unit,
    ): LlmChatResult {
        val snap = settings.current()
        enforceDailyBudget(snap)
        val ordered = snap.llmConnectionsOrdered()
        if (ordered.isEmpty()) {
            val legacyKey = snap.ollamaKey.trim()
            val legacyModel = snap.modelName.trim()
            if (legacyKey.isBlank() || legacyModel.isBlank()) {
                throw AppError.Configuration(
                    "No AI provider configured. Add Ollama or an OpenAI-compatible connection in Settings.",
                )
            }
            val text = ollama.chat(legacyModel, messages, jsonMode = jsonMode, apiKeyOverride = legacyKey)
            onDelta(text)
            return finishLegacy("legacy-ollama", "Ollama", legacyModel, messages, text)
        }

        val preferred = preferredConnectionId?.let { id -> ordered.firstOrNull { it.id == id } }
            ?: snap.activeProviderId?.let { id -> ordered.firstOrNull { it.id == id } }

        val queue = buildList {
            preferred?.let { add(it) }
            ordered.filter { it.id != preferred?.id }.forEach { add(it) }
        }

        var lastError: AppError? = null
        var fellBackFrom: String? = null
        var firstLabel: String? = null

        for ((index, conn) in queue.withIndex()) {
            if (index == 0) firstLabel = conn.label
            try {
                var captured: LlmUsage? = null
                val text = invokeOneStreaming(conn, messages, jsonMode, onDelta) { captured = it }
                val noteFrom = if (index > 0) firstLabel else null
                val usage = captured ?: TokenEstimator.estimateMessages(messages, text)
                recordUsage(conn, UsageKind.CHAT, usage)
                return LlmChatResult(
                    text = text,
                    connectionId = conn.id,
                    providerLabel = conn.label.ifBlank { conn.type.name },
                    fellBackFrom = noteFrom ?: fellBackFrom,
                    usage = usage,
                )
            } catch (e: AppError.RateLimited) {
                lastError = e
                if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                continue
            } catch (e: AppError) {
                if (isRateLimitLike(e) && index < queue.lastIndex) {
                    lastError = e
                    if (fellBackFrom == null && index == 0) fellBackFrom = conn.label
                    continue
                }
                throw e
            }
        }
        throw lastError ?: AppError.Configuration("Every configured AI provider failed.")
    }

    override suspend fun test(connection: ServiceConnection): String =
        when (connection.type) {
            ConnectionType.OLLAMA -> {
                val model = connection.modelName.trim().ifBlank {
                    settings.current().modelName.trim()
                }
                if (model.isBlank()) {
                    // version ping doesn't need a model
                    ollama.version()
                } else {
                    ollama.chat(
                        model = model,
                        messages = listOf(
                            OllamaMessage(
                                role = dev.repochat.core.model.OllamaRole.USER,
                                content = "Reply with exactly: ok",
                            ),
                        ),
                        jsonMode = false,
                        apiKeyOverride = connection.apiKey.trim().ifBlank { null },
                    ).take(80)
                }
            }
            ConnectionType.OPENAI_COMPATIBLE -> openAi.test(connection)
            ConnectionType.GITHUB -> throw AppError.Configuration("Not an LLM connection.")
        }

    override suspend fun listModels(connection: ServiceConnection): List<String> =
        when (connection.type) {
            ConnectionType.OLLAMA ->
                ollama.listModels(connection.apiKey.trim().ifBlank { null })
            ConnectionType.OPENAI_COMPATIBLE -> openAi.listModels(connection)
            ConnectionType.GITHUB -> emptyList()
        }

    private suspend fun invokeOne(
        conn: ServiceConnection,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        usageSink: ((LlmUsage) -> Unit)?,
    ): String = invokeOneStreaming(conn, messages, jsonMode, { }) { usageSink?.invoke(it) }

    private suspend fun invokeOneStreaming(
        conn: ServiceConnection,
        messages: List<OllamaMessage>,
        jsonMode: Boolean,
        onDelta: (String) -> Unit,
        usageSink: ((LlmUsage) -> Unit)? = null,
    ): String = when (conn.type) {
        ConnectionType.OLLAMA -> {
            val model = conn.modelName.trim().ifBlank {
                settings.current().modelName.trim()
            }
            if (model.isBlank()) {
                throw AppError.Configuration("Ollama connection \"${conn.label}\" has no model name.")
            }
            // Ollama service exposes no streaming API yet — one delta up front.
            val text = ollama.chat(
                model = model,
                messages = messages,
                jsonMode = jsonMode,
                apiKeyOverride = conn.apiKey.trim().ifBlank { null },
            )
            onDelta(text)
            text
        }
        ConnectionType.OPENAI_COMPATIBLE -> openAi.chatStream(conn, messages, jsonMode, onDelta, usageSink)
        ConnectionType.GITHUB -> throw AppError.Configuration("Not an LLM connection.")
    }

    /* ------------------------------ metering ------------------------------ */

    /** Finishes a legacy-path result: estimates usage and records the event. */
    private suspend fun finishLegacy(
        connectionId: String,
        providerLabel: String,
        model: String,
        messages: List<OllamaMessage>,
        text: String,
    ): LlmChatResult {
        val usage = TokenEstimator.estimateMessages(messages, text)
        recordUsageRaw(providerLabel, model, UsageKind.CHAT, usage)
        return LlmChatResult(
            text = text,
            connectionId = connectionId,
            providerLabel = providerLabel,
            usage = usage,
        )
    }

    /** Hard stop when today's token usage reaches [AppSettings.dailyTokenBudget]. */
    private suspend fun enforceDailyBudget(snap: AppSettings) {
        val budget = snap.dailyTokenBudget
        if (budget <= 0L) return
        val today = usageRepo.totalsSince(UsageClock.startOfToday())
        if (today.totalTokens >= budget) {
            throw AppError.Configuration(
                "Daily token budget reached — ${today.totalTokens} of $budget tokens used today. " +
                    "Raise or disable the budget in Settings → Usage & budget.",
            )
        }
    }

    private suspend fun recordUsage(
        conn: ServiceConnection,
        kind: UsageKind,
        usage: LlmUsage,
    ) {
        recordUsageRaw(
            provider = conn.label.ifBlank { conn.type.name },
            model = conn.modelName.trim(),
            kind = kind,
            usage = usage,
        )
    }

    /** Never let metering failures break a chat turn. */
    private suspend fun recordUsageRaw(
        provider: String,
        model: String,
        kind: UsageKind,
        usage: LlmUsage?,
    ) {
        runCatching {
            usageRepo.record(
                UsageEvent(
                    timestampMillis = System.currentTimeMillis(),
                    provider = provider.ifBlank { "unknown" },
                    model = model,
                    kind = kind,
                    inputTokens = usage?.inputTokens ?: 0L,
                    outputTokens = usage?.outputTokens ?: 0L,
                    reported = usage?.reported ?: false,
                ),
            )
        }
    }

    private fun isRateLimitLike(e: AppError): Boolean {
        if (e is AppError.RateLimited) return true
        val m = e.userMessage.lowercase()
        return "rate limit" in m || "too many requests" in m || "quota" in m ||
            "daily limit" in m || "tokens per day" in m
    }

    /* --------------------------- media generation --------------------------- */

    override fun hasCapability(capability: ModelCapability): Boolean {
        val snap = settings.cached()
        return snap.llmConnectionsOrdered().any { conn ->
            conn.type == ConnectionType.OPENAI_COMPATIBLE &&
                ModelCapabilities.supports(conn.modelName, capability)
        }
    }

    override suspend fun generateImage(
        prompt: String,
        preferredConnectionId: String?,
    ): GeneratedMedia {
        val candidates = mediaQueue(
            preferredConnectionId,
            ModelCapability.IMAGE_GEN,
            "image generation",
        )
        var lastError: AppError? = null
        for (conn in candidates) {
            try {
                val media = openAi.generateImage(conn, prompt)
                recordUsage(conn, UsageKind.IMAGE, LlmUsage(0, 0, reported = false))
                return media
            } catch (e: AppError.RateLimited) {
                lastError = e
                continue
            } catch (e: AppError) {
                if (isRateLimitLike(e) && conn != candidates.last()) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: AppError.Configuration("Every configured image provider failed.")
    }

    override suspend fun synthesizeSpeech(
        text: String,
        preferredConnectionId: String?,
    ): GeneratedMedia {
        val candidates = mediaQueue(
            preferredConnectionId,
            ModelCapability.AUDIO_GEN,
            "speech synthesis",
        )
        var lastError: AppError? = null
        for (conn in candidates) {
            try {
                val media = openAi.speech(conn, text)
                recordUsage(
                    conn,
                    UsageKind.SPEECH,
                    LlmUsage(TokenEstimator.estimate(text), 0, reported = false),
                )
                return media
            } catch (e: AppError.RateLimited) {
                lastError = e
                continue
            } catch (e: AppError) {
                if (isRateLimitLike(e) && conn != candidates.last()) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: AppError.Configuration("Every configured speech provider failed.")
    }

    /**
     * Ordered OPENAI_COMPATIBLE connections whose model advertises
     * [capability], honoring the preferred/active ordering first.
     */
    private suspend fun mediaQueue(
        preferredConnectionId: String?,
        capability: ModelCapability,
        featureLabel: String,
    ): List<ServiceConnection> {
        val snap = settings.current()
        val ordered = snap.llmConnectionsOrdered()
            .filter { it.type == ConnectionType.OPENAI_COMPATIBLE }
            .filter { ModelCapabilities.supports(it.modelName, capability) }
        if (ordered.isEmpty()) {
            throw AppError.Configuration(
                "$featureLabel needs a model that supports it (e.g. an image or TTS model) — " +
                    "no configured connection does. Add one in Settings.",
            )
        }
        val preferred = preferredConnectionId?.let { id -> ordered.firstOrNull { it.id == id } }
            ?: snap.activeProviderId?.let { id -> ordered.firstOrNull { it.id == id } }
        return buildList {
            preferred?.let { add(it) }
            ordered.filter { it.id != preferred?.id }.forEach { add(it) }
        }
    }
}
