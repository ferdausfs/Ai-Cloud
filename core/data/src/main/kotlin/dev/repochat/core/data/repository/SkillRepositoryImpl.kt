package dev.repochat.core.data.repository

import dev.repochat.core.data.local.SkillDao
import dev.repochat.core.data.local.SkillEntity
import dev.repochat.core.domain.GithubService
import dev.repochat.core.domain.SkillRepository
import dev.repochat.core.model.AppError
import dev.repochat.core.model.InstalledSkill
import dev.repochat.core.model.ParsedSkill
import dev.repochat.core.model.SkillInstallReport
import dev.repochat.core.model.SkillMarkdown
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed skill store + GitHub installer. Installation walks the repo
 * tree of the source, downloads every `SKILL.md` (Claude Code format) and
 * upserts them; re-installing a skill with an existing name updates it and
 * keeps its enabled flag.
 */
@Singleton
class SkillRepositoryImpl @Inject constructor(
    private val skillDao: SkillDao,
    private val github: GithubService,
) : SkillRepository {

    override fun installed(): Flow<List<InstalledSkill>> =
        skillDao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun enabledSkills(): List<InstalledSkill> =
        skillDao.enabled().map { it.toModel() }

    override suspend fun skill(name: String): InstalledSkill? =
        skillDao.byName(name)?.toModel()

    override suspend fun installFromGithub(source: String): SkillInstallReport {
        val src = GithubSkillSource.parse(source)
        val branch = src.branch ?: github.repoDefaultBranch(src.owner, src.repo)
        val label = "${src.owner}/${src.repo}@$branch" +
            (src.subdir?.let { " /$it" } ?: "")

        val tree = try {
            github.fileTree(src.owner, src.repo, branch)
        } catch (e: AppError.NotFound) {
            throw AppError.NotFound("Source not found: $label (check owner/repo and branch)")
        }

        // Collect SKILL.md paths: root-level, or any nested "<folder>/SKILL.md".
        val skillPaths = tree.entries
            .filter { it.type == "blob" }
            .map { it.path }
            .filter { it == SKILL_FILE || it.endsWith("/$SKILL_FILE") }
            .filter { src.subdir == null || it.startsWith("${src.subdir}/") || it == src.subdir }
            .filterNot { path -> EXCLUDED_SEGMENTS.any { seg -> path.split('/').contains(seg) } }
            .sorted()
            .take(MAX_SKILLS_PER_INSTALL)

        if (skillPaths.isEmpty()) {
            throw AppError.Configuration(
                "No SKILL.md found in $label. This source does not contain Claude-format agent skills."
            )
        }

        var installed = 0
        var unchanged = 0
        val result = mutableListOf<InstalledSkill>()
        for (path in skillPaths) {
            val file = github.fileContent(src.owner, src.repo, path, branch) ?: continue
            if (file.isBinary) continue
            val parsed = parseSkillFile(file.content, path) ?: continue
            val existing = skillDao.byName(parsed.name)
            val now = System.currentTimeMillis()
            val changed = existing == null ||
                existing.instructions != parsed.instructions ||
                existing.description != parsed.description

            val entity = SkillEntity(
                name = parsed.name,
                description = parsed.description,
                instructions = parsed.instructions,
                sourceRepo = "${src.owner}/${src.repo}",
                sourcePath = path,
                license = parsed.license,
                allowedTools = parsed.allowedTools,
                enabled = existing?.enabled ?: true,
                installedAt = existing?.installedAt ?: now,
                updatedAt = now,
            )
            skillDao.upsert(entity)
            result += entity.toModel()
            if (changed) installed++ else unchanged++
        }

        if (result.isEmpty()) {
            throw AppError.Configuration(
                "Found SKILL.md files in $label but none were valid — they need a 'name' or 'description' in the frontmatter."
            )
        }
        return SkillInstallReport(
            installedCount = installed,
            unchangedCount = unchanged,
            skills = result,
            sourceLabel = label,
        )
    }

    override suspend fun setEnabled(name: String, enabled: Boolean) =
        skillDao.setEnabled(name, enabled)

    override suspend fun delete(name: String) = skillDao.delete(name)

    /** Fallback name = first path segment holding the SKILL.md (or "skill"). */
    private fun parseSkillFile(content: String, path: String): ParsedSkill? {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        val fallback = parent.substringAfterLast('/').ifBlank { "skill" }
        return SkillMarkdown.parse(content, fallback)
    }

    private fun SkillEntity.toModel() = InstalledSkill(
        name = name,
        description = description,
        instructions = instructions,
        sourceRepo = sourceRepo,
        sourcePath = sourcePath,
        license = license,
        allowedTools = allowedTools,
        enabled = enabled,
        installedAt = installedAt,
        updatedAt = updatedAt,
    )

    private companion object {
        const val SKILL_FILE = "SKILL.md"
        const val MAX_SKILLS_PER_INSTALL = 50

        /** Skill trees often ship tests/docs next to skills; skip those subtrees. */
        val EXCLUDED_SEGMENTS = listOf("test", "tests", "node_modules", ".github", "docs", "script", "scripts")
    }
}

/**
 * Parsed GitHub skill source. Accepts `owner/repo`, full https URLs, and
 * `/tree/{branch}/{subfolder}` deep links (also `blob` links that point at a
 * SKILL.md file — the file name is then treated as the subfolder filter).
 */
data class GithubSkillSource(
    val owner: String,
    val repo: String,
    val branch: String? = null,
    val subdir: String? = null,
) {
    companion object {
        private val HOSTS = setOf("github.com", "www.github.com")

        fun parse(raw: String): GithubSkillSource {
            val input = raw.trim().trimEnd('/')
            if (input.isEmpty()) {
                throw AppError.Configuration("Enter a GitHub repo link, e.g. https://github.com/owner/repo")
            }

            // Bare "owner/repo" (optionally with deeper segments).
            val withoutScheme = input
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("git@github.com:")
            if (!withoutScheme.contains("/") || withoutScheme.contains(" ")) {
                throw AppError.Configuration(
                    "\"$input\" is not a GitHub source. Use owner/repo or a https://github.com link."
                )
            }

            val segments = withoutScheme.split('/').filter { it.isNotEmpty() }
            if (segments.size == 1 || (HOSTS.contains(segments[0]) && segments.size < 3)) {
                throw AppError.Configuration(
                    "\"$input\" is missing the repository name. Use owner/repo."
                )
            }

            val (owner, repo, rest) = if (HOSTS.contains(segments[0])) {
                Triple(segments[1], segments[2].removeSuffix(".git"), segments.drop(3))
            } else {
                Triple(segments[0], segments[1].removeSuffix(".git"), segments.drop(2))
            }
            if (owner.isBlank() || repo.isBlank()) {
                throw AppError.Configuration("Could not read owner/repo from \"$input\".")
            }
            if (rest.isEmpty()) return GithubSkillSource(owner, repo)

            // /tree/{branch}[/subdir...] or /blob/{branch}/{path...}
            val kind = rest.firstOrNull()?.lowercase()
            if (kind != "tree" && kind != "blob") {
                // Unknown extra segments (issues/, pulls/, ...) — just use the repo.
                return GithubSkillSource(owner, repo)
            }
            val branchAndPath = rest.drop(1)
            if (branchAndPath.isEmpty()) return GithubSkillSource(owner, repo)

            // Common convention: {major}.x maintenance branches — split on the
            // first segment that looks like a branch is unreliable, so treat
            // everything after the first segment as path only when it does not
            // contain a '/'-free marker. GitHub URLs put the branch first.
            val branch = branchAndPath.first()
            val path = branchAndPath.drop(1).joinToString("/").trimEnd('/')
            val normalizedPath = path.substringBeforeLast("/SKILL.md")
                .ifBlank { path }
            return GithubSkillSource(
                owner = owner,
                repo = repo,
                branch = branch.takeIf { it.isNotBlank() },
                subdir = normalizedPath.takeIf { it.isNotBlank() && path != "SKILL.md" },
            )
        }
    }
}
