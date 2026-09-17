package dev.repochat.core.domain

import dev.repochat.core.model.InstalledSkill
import dev.repochat.core.model.SkillInstallReport
import kotlinx.coroutines.flow.Flow

/**
 * Installed agent skills (Claude Code SKILL.md compatible). Skills are
 * installed from GitHub sources, stored locally, and injected into every AI
 * turn's prompt: name + description always, full instructions when they fit
 * or on demand via the read_skill action.
 */
interface SkillRepository {

    /** All installed skills, newest install first. */
    fun installed(): Flow<List<InstalledSkill>>

    /** One-shot read of enabled skills only (prompt building). */
    suspend fun enabledSkills(): List<InstalledSkill>

    /** Lookup by normalized name (read_skill action). */
    suspend fun skill(name: String): InstalledSkill?

    /**
     * Installs every SKILL.md found in a GitHub source. [source] accepts:
     *  - `owner/repo`
     *  - `https://github.com/owner/repo`
     *  - `https://github.com/owner/repo/tree/{branch}/{optional subfolder}`
     *
     * Throws [dev.repochat.core.model.AppError] with a user-readable message
     * when the source cannot be reached or contains no SKILL.md.
     */
    suspend fun installFromGithub(source: String): SkillInstallReport

    suspend fun setEnabled(name: String, enabled: Boolean)

    suspend fun delete(name: String)
}
