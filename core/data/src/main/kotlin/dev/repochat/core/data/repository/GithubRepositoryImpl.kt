package dev.repochat.core.data.repository

import android.util.Base64
import dev.repochat.core.data.remote.GithubApi
import dev.repochat.core.data.remote.GithubCreatePullRequestDto
import dev.repochat.core.data.remote.GithubCreateRefRequestDto
import dev.repochat.core.data.remote.GithubFileContentDto
import dev.repochat.core.data.remote.GithubPutFileRequestDto
import dev.repochat.core.data.remote.GithubRepoDto
import dev.repochat.core.data.remote.mapHttpErrors
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
import dev.repochat.core.model.WorkflowStepInfo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Singleton
class GithubRepositoryImpl @Inject constructor(
    private val api: GithubApi,
) : GithubService {

    override suspend fun listRepos(): List<RepoSummary> {
        val all = mutableListOf<RepoSummary>()
        for (page in 1..MAX_PAGES) {
            val batch = mapHttpErrors(AppError.Provider.GITHUB) {
                api.listRepos(perPage = 100, page = page)
            }
            all += batch.map { it.toModel() }
            if (batch.size < 100) break
        }
        return all
    }

    override suspend fun currentUserLogin(): String =
        mapHttpErrors(AppError.Provider.GITHUB) { api.currentUser().login }

    override suspend fun ensureWorkingBranch(
        owner: String,
        repo: String,
        sessionId: String,
        defaultBranch: String,
    ): String {
        val branch = "ai-chat/$sessionId"
        val existing = try {
            mapHttpErrors(AppError.Provider.GITHUB) { api.branch(owner, repo, branch) }
        } catch (e: AppError.NotFound) {
            null
        }
        if (existing != null) return branch

        // Branch does not exist yet: create it from the default branch HEAD.
        val head = mapHttpErrors(AppError.Provider.GITHUB) {
            api.branch(owner, repo, defaultBranch)
        }
        mapHttpErrors(AppError.Provider.GITHUB) {
            api.createRef(
                owner = owner,
                repo = repo,
                body = GithubCreateRefRequestDto(ref = "refs/heads/$branch", sha = head.obj.sha),
            )
        }
        return branch
    }

    override suspend fun fileTree(owner: String, repo: String, branch: String): RepoFileTree {
        val response = mapHttpErrors(AppError.Provider.GITHUB) {
            api.tree(owner = owner, repo = repo, ref = branch, recursive = 1)
        }
        return RepoFileTree(
            entries = response.tree
                .filter { it.type == "blob" || it.type == "tree" }
                .map { TreeEntry(path = it.path, type = it.type) },
            truncated = response.truncated,
        )
    }

    override suspend fun fileContent(owner: String, repo: String, path: String, branch: String): GitFile? {
        val dto = try {
            mapHttpErrors(AppError.Provider.GITHUB) {
                api.file(contentsUrl(owner, repo, path, branch))
            }
        } catch (e: AppError.NotFound) {
            return null
        }
        return dto.toGitFile(path)
    }

    override suspend fun commitFile(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        branch: String,
        baseSha: String?,
        commitMessage: String,
    ): CommitResult {
        val body = GithubPutFileRequestDto(
            message = commitMessage,
            content = Base64.encodeToString(newContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
            sha = baseSha,
            // The working branch is ALWAYS passed explicitly — omitting it
            // would default the commit to main, which is forbidden.
            branch = branch,
        )
        val response = mapHttpErrors(AppError.Provider.GITHUB) {
            api.putFile(contentsUrl(owner, repo, path, branch), body)
        }
        return CommitResult(path = path, newSha = response.content?.sha.orEmpty())
    }

    override suspend fun createPullRequest(
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PullRequestInfo {
        val dto = mapHttpErrors(AppError.Provider.GITHUB) {
            api.createPullRequest(
                owner = owner,
                repo = repo,
                body = GithubCreatePullRequestDto(title = title, head = head, base = base, body = body),
            )
        }
        return PullRequestInfo(number = dto.number ?: 0L, htmlUrl = dto.htmlUrl, title = dto.title)
    }

    override suspend fun listWorkflowRuns(
        owner: String,
        repo: String,
        branch: String,
        perPage: Int,
    ): List<WorkflowRunInfo> {
        val dto = mapHttpErrors(AppError.Provider.GITHUB) {
            api.listWorkflowRuns(owner = owner, repo = repo, branch = branch, perPage = perPage)
        }
        return dto.runs.map { run ->
            WorkflowRunInfo(
                id = run.id,
                name = run.name.orEmpty(),
                status = run.status.orEmpty().ifBlank { "unknown" },
                conclusion = run.conclusion,
                htmlUrl = run.htmlUrl,
                updatedAtMillis = run.updatedAt?.let(::parseIso8601),
            )
        }
    }

    override suspend fun listJobsForRun(
        owner: String,
        repo: String,
        runId: Long,
    ): List<WorkflowJobInfo> {
        val dto = mapHttpErrors(AppError.Provider.GITHUB) {
            api.listJobsForRun(owner = owner, repo = repo, runId = runId)
        }
        return dto.jobs.map { job ->
            WorkflowJobInfo(
                id = job.id,
                name = job.name,
                conclusion = job.conclusion,
                status = job.status.orEmpty(),
                steps = job.steps.map { step ->
                    WorkflowStepInfo(
                        name = step.name,
                        conclusion = step.conclusion,
                        number = step.number,
                    )
                },
            )
        }
    }

    override suspend fun getJobLogs(
        owner: String,
        repo: String,
        jobId: Long,
    ): String {
        val body = mapHttpErrors(AppError.Provider.GITHUB) {
            api.getJobLogs(owner = owner, repo = repo, jobId = jobId)
        }
        return body.use { it.string() }
    }

    /* ---------------------------- helpers ---------------------------- */

    private fun contentsUrl(owner: String, repo: String, path: String, branch: String): HttpUrl =
        API_BASE.newBuilder()
            .addPathSegment("repos")
            .addPathSegment(owner)
            .addPathSegment(repo)
            .addPathSegment("contents")
            .addPathSegments(path)
            .addQueryParameter("ref", branch)
            .build()

    private fun GithubRepoDto.toModel(): RepoSummary = RepoSummary(
        id = id,
        name = name,
        fullName = fullName,
        isPrivate = isPrivate,
        description = description,
        language = language,
        updatedAtMillis = updatedAt?.let(::parseIso8601),
        defaultBranch = defaultBranch.ifBlank { "main" },
        htmlUrl = htmlUrl,
        stargazersCount = stargazersCount,
    )

    private fun GithubFileContentDto.toGitFile(path: String): GitFile {
        val raw = content.orEmpty().replace("\n", "")
        val bytes = try {
            Base64.decode(raw, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            ByteArray(0)
        }
        val isBinary = bytes.contains(0.toByte())
        val text = if (isBinary) "" else String(bytes, Charsets.UTF_8)
        return GitFile(
            path = path,
            content = text,
            sha = sha.orEmpty(),
            sizeBytes = size ?: bytes.size.toLong(),
            isBinary = isBinary,
        )
    }

    private companion object {
        const val MAX_PAGES = 10
        val API_BASE: HttpUrl = "https://api.github.com/".toHttpUrl()
    }

    private fun parseIso8601(value: String): Long = try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(value)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }
}
