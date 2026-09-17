package dev.repochat.core.model

/**
 * Minimal, dependency-free parser for the Claude Code `SKILL.md` format:
 *
 * ```
 * ---
 * name: pdf-processing
 * description: Extract text and tables from PDF files...
 * license: MIT          (optional)
 * allowed-tools: ...    (optional)
 * unknown-key: ignored  (optional)
 * ---
 * Markdown instructions for the agent.
 * ```
 *
 * Leniency rules (mobile installs must be forgiving):
 *  - `name` may be omitted → derived from the file name / parent folder.
 *  - Values may be single- or double-quoted.
 *  - Unknown frontmatter keys are ignored.
 *  - [parse] returns null only when the file clearly is not a usable skill
 *    (no description AND no instructions body).
 */
object SkillMarkdown {

    private const val FRONTMATTER_DELIM = "---"
    private const val MAX_NAME_LENGTH = 64
    private const val MAX_DESCRIPTION_LENGTH = 1_024
    private const val MAX_INSTRUCTIONS_LENGTH = 120_000

    /**
     * Parses one SKILL.md file. [fallbackName] is used when the frontmatter
     * has no `name` (usually the parent folder name, e.g. "pdf").
     */
    fun parse(raw: String, fallbackName: String): ParsedSkill? {
        if (raw.isBlank()) return null
        val (frontmatter, body) = splitFrontmatter(raw)
        val keys = parseFrontmatterKeys(frontmatter)

        val name = normalizeName(
            keys["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
        ) ?: return null

        val description = keys["description"].orEmpty().trim()
        val instructions = body.trim()
        if (description.isBlank() && instructions.isBlank()) return null

        return ParsedSkill(
            name = name,
            description = description.take(MAX_DESCRIPTION_LENGTH),
            instructions = instructions.take(MAX_INSTRUCTIONS_LENGTH),
            license = keys["license"]?.takeIf { it.isNotBlank() },
            allowedTools = keys["allowed-tools"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Splits `---\nkey: value\n---\nbody` into (raw frontmatter, body). */
    private fun splitFrontmatter(raw: String): Pair<String, String> {
        val text = raw.replace("\r\n", "\n").removePrefix("\uFEFF")
        if (!text.startsWith(FRONTMATTER_DELIM)) return "" to text
        val lines = text.split("\n")
        if (lines.first().trim() != FRONTMATTER_DELIM) return "" to text
        var closeIndex = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == FRONTMATTER_DELIM) {
                closeIndex = i
                break
            }
        }
        if (closeIndex == -1) return "" to text
        val frontmatter = lines.subList(1, closeIndex).joinToString("\n")
        val body = lines.subList(closeIndex + 1, lines.size).joinToString("\n")
        return frontmatter to body
    }

    /** `key: value` pairs; quoted values are unquoted, unknown keys kept. */
    private fun parseFrontmatterKeys(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (line in frontmatter.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim().lowercase()
            val value = trimmed.substring(colon + 1).trim().unquote()
            if (key.isNotEmpty()) map[key] = value
        }
        return map
    }

    private fun String.unquote(): String {
        val t = trim()
        if (t.length >= 2) {
            val first = t.first()
            val last = t.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return t.substring(1, t.length - 1).trim()
            }
        }
        return t
    }

    /**
     * Normalizes a skill name to the kebab-case id used by Claude Code
     * (lowercase letters, digits, hyphens). Returns null when nothing usable
     * remains — e.g. a name made only of CJK/symbols.
     */
    private fun normalizeName(raw: String): String? {
        val cleaned = raw.trim().lowercase()
            .replace(Regex("[^a-z0-9\\-]+"), "-")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
        if (cleaned.isEmpty()) return null
        return cleaned.take(MAX_NAME_LENGTH)
    }
}
