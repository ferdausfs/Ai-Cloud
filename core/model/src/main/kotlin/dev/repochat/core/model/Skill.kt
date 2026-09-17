package dev.repochat.core.model

/**
 * One installed agent skill, compatible with the Claude Code `SKILL.md`
 * convention: a markdown file with YAML frontmatter (`name`, `description`,
 * optional `license`, `allowed-tools`) followed by free-form instructions.
 *
 * The name is the unique identifier — installing a skill whose name already
 * exists updates it in place (keeps the enabled flag and install date).
 */
data class InstalledSkill(
    /** Unique id, e.g. "pdf-processing" (normalized lowercase kebab-case). */
    val name: String,
    /** One-line description used by the model to decide when the skill applies. */
    val description: String,
    /** Full markdown instructions (the SKILL.md body). */
    val instructions: String,
    /** Where it came from, e.g. "anthropics/skills". */
    val sourceRepo: String,
    /** Path of the SKILL.md inside the source repo. */
    val sourcePath: String,
    val license: String? = null,
    /** Raw `allowed-tools` frontmatter value, kept for compatibility only. */
    val allowedTools: String? = null,
    /** Disabled skills stay installed but never reach the prompt. */
    val enabled: Boolean = true,
    val installedAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/** Result of installing skills from one GitHub source. */
data class SkillInstallReport(
    /** Skills whose content changed or that were newly created. */
    val installedCount: Int,
    /** Skills that were already installed with identical content. */
    val unchangedCount: Int,
    val skills: List<InstalledSkill>,
    /** Human label of the source, e.g. "anthropics/skills@main". */
    val sourceLabel: String,
)

/**
 * Intermediate parse result for one SKILL.md file — before persistence
 * decides whether it is new or an update.
 */
data class ParsedSkill(
    val name: String,
    val description: String,
    val instructions: String,
    val license: String? = null,
    val allowedTools: String? = null,
)

/**
 * Media produced by a generation endpoint (image or speech). Base64 payload
 * is stored directly on the chat message row.
 */
data class GeneratedMedia(
    val base64: String,
    val mimeType: String,
    val providerLabel: String,
)
