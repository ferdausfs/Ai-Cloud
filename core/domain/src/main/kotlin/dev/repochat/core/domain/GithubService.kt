package dev.repochat.core.domain

import dev.repochat.core.model.CommitResult
import dev.repochat.core.model.GitFile
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoFileTree
import dev.repochat.core.model.RepoSummary
import dev.repochat.core.model.WorkflowRunInfo

/**
 * Everything the app needs from the GitHub REST API (v3, api.github.com).
 * Implementations must translate HTTP/IO failures into [AppError] subtypes.
 */
interface GithubService {

    suspend fun listRepos(): List<RepoSummary>

    /** Used by the Settings "Test connection" — returns the authenticated login. */
    suspend fun currentUserLogin(): String

    /**
     * Returns the working branch for [sessionId], creating it from the
     * [defaultBranch] HEAD if it does not exist yet. Never returns main/master.
     */
    suspend fun ensureWorkingBranch(owner: String, repo: String, sessionId: String, defaultBranch: String): String

    suspend fun fileTree(owner: String, repo: String, branch: String): RepoFileTree

    /** Returns null when the file does not exist on [branch] (HTTP 404). */
    suspend fun fileContent(owner: String, repo: String, path: String, branch: String): GitFile?

    /** Commits new file content to [branch] — branch is always passed explicitly. */
    suspend fun commitFile(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        branch: String,
        baseSha: String?,
        commitMessage: String,
    ): CommitResult

    suspend fun createPullRequest(
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PullRequestInfo

    /**
     * Recent GitHub Actions workflow runs for [branch], most recent first.
     * Empty list when the repo has no Actions history for that branch.
     */
    suspend fun listWorkflowRuns(
        owner: String,
        repo: String,
        branch: String,
        perPage: Int = 5,
    ): List<WorkflowRunInfo>
}
