package dev.repochat.core.domain

import dev.repochat.core.model.ActiveRepo
import dev.repochat.core.model.AppError
import dev.repochat.core.model.AppSettings
import dev.repochat.core.model.ConnectionResult
import dev.repochat.core.model.PullRequestInfo
import dev.repochat.core.model.RepoSummary
import javax.inject.Inject

class ListReposUseCase @Inject constructor(
    private val github: GithubService,
) {
    suspend operator fun invoke(): List<RepoSummary> = github.listRepos()
}

class SetActiveRepoUseCase @Inject constructor(
    private val activeRepo: ActiveRepoRepository,
) {
    suspend operator fun invoke(repo: RepoSummary) {
        activeRepo.set(
            ActiveRepo(
                repoKey = repo.fullName,
                owner = repo.owner,
                repo = repo.name,
                defaultBranch = repo.defaultBranch,
                selectedAt = System.currentTimeMillis(),
            )
        )
    }
}

class SaveSettingsUseCase @Inject constructor(
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(newSettings: AppSettings) = settings.save(newSettings)
}

class TestOllamaUseCase @Inject constructor(
    private val ollama: OllamaService,
) {
    suspend operator fun invoke(): ConnectionResult = try {
        ConnectionResult(true, "Connected — Ollama API v${ollama.version()}")
    } catch (e: AppError) {
        ConnectionResult(false, e.userMessage)
    } catch (e: Exception) {
        ConnectionResult(
            false,
            "Could not reach Ollama: ${e.message?.takeIf { it.isNotBlank() } ?: "network error"}",
        )
    }
}

class TestGithubUseCase @Inject constructor(
    private val github: GithubService,
) {
    suspend operator fun invoke(): ConnectionResult = try {
        ConnectionResult(true, "Connected as @${github.currentUserLogin()}")
    } catch (e: AppError) {
        ConnectionResult(false, e.userMessage)
    } catch (e: Exception) {
        ConnectionResult(
            false,
            "Could not reach GitHub: ${e.message?.takeIf { it.isNotBlank() } ?: "network error"}",
        )
    }
}

class CreatePullRequestUseCase @Inject constructor(
    private val github: GithubService,
) {
    suspend operator fun invoke(
        owner: String,
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
    ): PullRequestInfo = github.createPullRequest(owner, repo, head, base, title, body)
}
