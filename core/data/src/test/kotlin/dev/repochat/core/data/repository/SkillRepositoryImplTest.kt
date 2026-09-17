package dev.repochat.core.data.repository

import dev.repochat.core.data.local.SkillDao
import dev.repochat.core.data.local.SkillEntity
import dev.repochat.core.domain.GithubService
import dev.repochat.core.model.AppError
import dev.repochat.core.model.CommitResult
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoFileTree
import dev.repochat.core.model.RepoSummary
import dev.repochat.core.model.TreeEntry
import dev.repochat.core.model.WorkflowJobInfo
import dev.repochat.core.model.WorkflowRunInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

class SkillRepositoryImplTest {

    /* ----------------------------- fakes ----------------------------- */

    private class FakeSkillDao : SkillDao {
        val rows = LinkedHashMap<String, SkillEntity>()
        private val flow = MutableStateFlow<List<SkillEntity>>(emptyList())

        private fun publish() {
            flow.value = rows.values.sortedByDescending { it.installedAt }
        }

        override fun observeAll(): Flow<List<SkillEntity>> = flow.map { it }

        override suspend fun enabled(): List<SkillEntity> = rows.values.filter { it.enabled }

        override suspend fun byName(name: String): SkillEntity? = rows[name]

        override suspend fun all(): List<SkillEntity> = rows.values.toList()

        override suspend fun upsert(skill: SkillEntity) {
            rows[skill.name] = skill
            publish()
        }

        override suspend fun setEnabled(name: String, enabled: Boolean) {
            rows[name]?.let { rows[name] = it.copy(enabled = enabled) }
            publish()
        }

        override suspend fun delete(name: String) {
            rows.remove(name)
            publish()
        }
    }

    private class FakeGithubForSkills : GithubService {
        var tree: List<TreeEntry> = emptyList()
        val files = mutableMapOf<String, String>()
        var defaultBranch = "main"
        var failTreeWith: AppError? = null

        override suspend fun listRepos(): List<RepoSummary> = emptyList()
        override suspend fun currentUserLogin(): String = "tester"
        override suspend fun repoDefaultBranch(owner: String, repo: String): String = defaultBranch

        override suspend fun ensureWorkingBranch(
            owner: String,
            repo: String,
            sessionId: String,
            defaultBranch: String,
        ): String = "ai-chat/$sessionId"

        override suspend fun fileTree(owner: String, repo: String, branch: String): RepoFileTree {
            failTreeWith?.let { throw it }
            return RepoFileTree(tree, truncated = false)
        }

        override suspend fun fileContent(
            owner: String,
            repo: String,
            path: String,
            branch: String,
        ): GitFile? = files[path]?.let {
            GitFile(path, it, sha = "sha-$path", sizeBytes = it.length.toLong(), isBinary = false)
        }

        override suspend fun commitFile(
            owner: String,
            repo: String,
            path: String,
            newContent: String,
            branch: String,
            baseSha: String?,
            commitMessage: String,
        ): CommitResult = CommitResult(path, "new-sha")

        override suspend fun createPullRequest(
            owner: String,
            repo: String,
            head: String,
            base: String,
            title: String,
            body: String,
        ): PullRequestInfo = PullRequestInfo(1, "url", title)

        override suspend fun listWorkflowRuns(
            owner: String,
            repo: String,
            branch: String,
            perPage: Int,
        ): List<WorkflowRunInfo> = emptyList()

        override suspend fun listJobsForRun(
            owner: String,
            repo: String,
            runId: Long,
        ): List<WorkflowJobInfo> = emptyList()

        override suspend fun getJobLogs(owner: String, repo: String, jobId: Long): String = ""
    }

    /* ----------------------------- tests ----------------------------- */

    private fun skillFile(name: String, description: String, body: String = "Do the thing.") =
        "---\nname: $name\ndescription: $description\n---\n$body"

    @Test
    fun `installs root and nested skills`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            tree = listOf(
                TreeEntry("SKILL.md", "blob"),
                TreeEntry("skills/pdf/SKILL.md", "blob"),
                TreeEntry("README.md", "blob"),
            )
            files["SKILL.md"] = skillFile("root-skill", "Root level skill.")
            files["skills/pdf/SKILL.md"] = skillFile("pdf", "Handles PDFs.")
            files["README.md"] = "# not a skill"
        }
        val repo = SkillRepositoryImpl(dao, gh)

        val report = repo.installFromGithub("acme/skillpack")

        assertEquals(2, report.installedCount)
        assertEquals(setOf("root-skill", "pdf"), report.skills.map { it.name }.toSet())
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.values.all { it.enabled })
        assertTrue(dao.rows.values.all { it.sourceRepo == "acme/skillpack" })
    }

    @Test
    fun `reinstall updates content and keeps enabled flag`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            tree = listOf(TreeEntry("SKILL.md", "blob"))
            files["SKILL.md"] = skillFile("deploy", "Old description.", "old body")
        }
        val repo = SkillRepositoryImpl(dao, gh)
        repo.installFromGithub("acme/skillpack")
        repo.setEnabled("deploy", false)

        gh.files["SKILL.md"] = skillFile("deploy", "New description.", "new body")
        val second = repo.installFromGithub("acme/skillpack")

        assertEquals(1, second.installedCount)
        assertEquals(false, dao.rows["deploy"]?.enabled)
        assertEquals("New description.", dao.rows["deploy"]?.description)
        assertEquals("new body", dao.rows["deploy"]?.instructions)

        // Third install with identical content → unchanged count
        val third = repo.installFromGithub("acme/skillpack")
        assertEquals(0, third.installedCount)
        assertEquals(1, third.unchangedCount)
    }

    @Test
    fun `skips tests and docs subtrees`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            tree = listOf(
                TreeEntry("skills/alpha/SKILL.md", "blob"),
                TreeEntry("tests/alpha/SKILL.md", "blob"),
                TreeEntry("docs/guide/SKILL.md", "blob"),
            )
            files["skills/alpha/SKILL.md"] = skillFile("alpha", "Real skill.")
            files["tests/alpha/SKILL.md"] = skillFile("alpha-tests", "Should be ignored.")
            files["docs/guide/SKILL.md"] = skillFile("guide", "Should be ignored.")
        }
        val repo = SkillRepositoryImpl(dao, gh)

        val report = repo.installFromGithub("acme/skillpack")

        assertEquals(1, report.installedCount)
        assertEquals("alpha", report.skills.single().name)
    }

    @Test
    fun `subdir filter only installs that folder`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            tree = listOf(
                TreeEntry("skills/pdf/SKILL.md", "blob"),
                TreeEntry("skills/xlsx/SKILL.md", "blob"),
            )
            files["skills/pdf/SKILL.md"] = skillFile("pdf", "PDFs.")
            files["skills/xlsx/SKILL.md"] = skillFile("xlsx", "Sheets.")
        }
        val repo = SkillRepositoryImpl(dao, gh)

        val report = repo.installFromGithub("https://github.com/acme/skillpack/tree/main/skills/pdf")

        assertEquals(setOf("pdf"), report.skills.map { it.name }.toSet())
    }

    @Test
    fun `no skills found raises friendly configuration error`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            tree = listOf(TreeEntry("README.md", "blob"), TreeEntry("src/Main.kt", "blob"))
            files["README.md"] = "# hello"
            files["src/Main.kt"] = "fun main() {}"
        }
        val repo = SkillRepositoryImpl(dao, gh)

        try {
            repo.installFromGithub("acme/plainrepo")
            fail("expected AppError.Configuration")
        } catch (e: AppError.Configuration) {
            assertTrue(e.userMessage.contains("No SKILL.md"))
        }
    }

    @Test
    fun `tree 404 surfaces as not found`() = runTest {
        val dao = FakeSkillDao()
        val gh = FakeGithubForSkills().apply {
            failTreeWith = AppError.NotFound("repo missing")
        }
        val repo = SkillRepositoryImpl(dao, gh)

        try {
            repo.installFromGithub("acme/ghost")
            fail("expected AppError.NotFound")
        } catch (e: AppError.NotFound) {
            assertTrue(e.userMessage.contains("acme/ghost"))
        }
    }

    @Test
    fun `source parser handles the documented forms`() {
        assertEquals(
            GithubSkillSource("acme", "skills"),
            GithubSkillSource.parse("acme/skills"),
        )
        assertEquals(
            GithubSkillSource("acme", "skills"),
            GithubSkillSource.parse("https://github.com/acme/skills/"),
        )
        val deep = GithubSkillSource.parse("https://github.com/anthropics/skills/tree/main/document-skills/pdf")
        assertEquals(GithubSkillSource("anthropics", "skills", branch = "main", subdir = "document-skills/pdf"), deep)
        // blob link to a SKILL.md → same as tree of its folder
        val blob = GithubSkillSource.parse("https://github.com/acme/skills/blob/main/pdf/SKILL.md")
        assertEquals(GithubSkillSource("acme", "skills", branch = "main", subdir = "pdf"), blob)
    }

    @Test
    fun `source parser rejects garbage`() {
        try {
            GithubSkillSource.parse("not a github link at all!!")
            fail("expected AppError.Configuration")
        } catch (e: AppError.Configuration) {
            // expected
        }
    }
}
