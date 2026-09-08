package dev.repochat.core.model

/**
 * Pricing visibility for one model id, as shown on model badges/filters.
 *
 * The app NEVER invents pricing: [PAID] is only claimed when the provider's
 * own catalog semantics prove it (e.g. OpenRouter, where every non-`:free`
 * route bills at provider price), and anything we cannot verify stays
 * [UNKNOWN] — displayed as "Pricing unavailable".
 */
enum class ModelPriceClass {
    /** Verified zero-cost route (e.g. an OpenRouter `:free` id). */
    FREE,

    /**
     * Promotional free tier granted by the provider for a limited time.
     * Availability is determined by the provider and may change at any time;
     * the app never claims such a model is permanently free.
     */
    PROMOTIONAL,

    /** Verified paid route (provider catalog semantics, not a guess). */
    PAID,

    /** Pricing information is unavailable — shown as "Pricing unavailable". */
    UNKNOWN,
}

/**
 * Classifies model ids into [ModelPriceClass] per provider and sorts lists
 * free-tier-first so the model picker surfaces zero-cost options up top.
 */
object ModelPricing {

    /**
     * Experiential Labs slugs that currently carry a promotional free daily
     * tier (source: provider billing docs, September 2026). This snapshot is
     * a UI hint only — live catalog listing always wins, and the promo set
     * can change without an app update.
     */
    val KNOWN_PROMOTIONAL_SLUGS: Set<String> = setOf(
        "gpt-6-astra",
        "claude-fable-5.1",
    )

    /** Experiential Labs preset label (see [KNOWN_OPENAI_PROVIDERS]). */
    const val EXPERIENTIAL_LABEL: String = "Experiential Labs"

    /** OpenRouter preset label — non-`:free` ids bill at provider price. */
    const val OPENROUTER_LABEL: String = "OpenRouter"

    fun classify(modelId: String, providerLabel: String): ModelPriceClass {
        val id = modelId.trim()
        if (id.isEmpty()) return ModelPriceClass.UNKNOWN
        if (id.lowercase().endsWith(":free")) return ModelPriceClass.FREE
        return when (providerLabel.trim()) {
            EXPERIENTIAL_LABEL ->
                if (id.lowercase() in KNOWN_PROMOTIONAL_SLUGS) {
                    ModelPriceClass.PROMOTIONAL
                } else {
                    ModelPriceClass.UNKNOWN
                }
            OPENROUTER_LABEL -> ModelPriceClass.PAID
            else -> ModelPriceClass.UNKNOWN
        }
    }

    /** Free OR promotional — models a user can try at zero cost today. */
    fun isFreeTier(modelId: String, providerLabel: String): Boolean =
        when (classify(modelId, providerLabel)) {
            ModelPriceClass.FREE, ModelPriceClass.PROMOTIONAL -> true
            else -> false
        }

    /** Badge text for the model picker ("FREE" / "PROMO" / "PAID" / "N/A"). */
    fun badgeLabel(priceClass: ModelPriceClass): String = when (priceClass) {
        ModelPriceClass.FREE -> "FREE"
        ModelPriceClass.PROMOTIONAL -> "PROMO"
        ModelPriceClass.PAID -> "PAID"
        ModelPriceClass.UNKNOWN -> "N/A"
    }

    /**
     * Free/promotional models first, then the rest — alphabetical within
     * each group. Stable, no invented pricing: [UNKNOWN] just sorts last.
     */
    fun sortFreeFirst(models: List<String>, providerLabel: String): List<String> {
        val rank = { id: String ->
            when (classify(id, providerLabel)) {
                ModelPriceClass.FREE -> 0
                ModelPriceClass.PROMOTIONAL -> 1
                ModelPriceClass.PAID -> 2
                ModelPriceClass.UNKNOWN -> 3
            }
        }
        return models.sortedWith(compareBy({ rank(it) }, { it.lowercase() }))
    }
}
