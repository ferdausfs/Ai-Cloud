package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionResult
import dev.repochat.core.model.VercelDeploymentInfo
import dev.repochat.core.model.VercelProjectInfo

/** Vercel REST API: read deployments + trigger new deployments.
 * Both are supported directly by Vercel's API. */
interface VercelService {
    suspend fun test(apiKey: String): ConnectionResult
    suspend fun listProjects(apiKey: String): List<VercelProjectInfo>
    suspend fun listDeployments(apiKey: String, projectId: String?): List<VercelDeploymentInfo>
    suspend fun triggerDeployment(apiKey: String, projectId: String, ignoreCache: Boolean): ConnectionResult
}
