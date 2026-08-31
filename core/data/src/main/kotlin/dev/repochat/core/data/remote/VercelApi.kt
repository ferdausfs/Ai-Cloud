package dev.repochat.core.data.remote

import kotlinx.serialization.Serializable

/** Vercel REST API (api.vercel.com) — both read (deployments) and write
 * (trigger a new deployment) are fine: Vercel's API supports both directly.
 */
interface VercelApi {

    /** GET /v2/projects — list projects for the authenticated user/team. */
    @retrofit2.http.GET("v2/projects")
    suspend fun listProjects(): VercelProjectsDto

    /** GET /v2/deployments — list recent deployments (optionally filtered by
     * project id via query param built through the caller). */
    @retrofit2.http.GET("v2/deployments")
    suspend fun listDeployments(
        @retrofit2.http.Query("projectId") projectId: String? = null,
        @retrofit2.http.Query("limit") limit: Int = 10,
    ): VercelDeploymentsDto

    /** POST /v2/deployments — trigger a new deployment for a project.
     * Returns 200/201 with the deployment object; the app pipes the result
     * back to the user rather than blocking on the build. */
    @retrofit2.http.POST("v2/deployments")
    suspend fun createDeployment(body: VercelCreateDeploymentDto): VercelDeploymentDto
}

/* ---------------------------- DTOs ---------------------------- */

@Serializable
data class VercelProjectsDto(val projects: List<VercelProjectDto> = emptyList())

@Serializable
data class VercelProjectDto(
    val id: String = "",
    val name: String = "",
    val framework: String? = null,
    val ownerId: String? = null,
)

@Serializable
data class VercelDeploymentsDto(val deployments: List<VercelDeploymentDto> = emptyList())

@Serializable
data class VercelDeploymentDto(
    val id: String = "",
    val url: String = "",
    val status: String = "",
    val state: String = "",
    val creator: VercelCreatorDto? = null,
    val project: VercelProjectReferenceDto? = null,
)

@Serializable
data class VercelCreatorDto(val username: String? = null, val email: String? = null)

@Serializable
data class VercelProjectReferenceDto(val id: String = "", val name: String = "")

/** Body for [VercelApi.createDeployment]. Minimal trigger payload — the app
 * does not attempt to upload source; it relies on the project's existing
 * git/Vercel integration. The user can also trigger from the Vercel dashboard. */
@Serializable
data class VercelCreateDeploymentDto(
    val projectId: String,
    val ignoreCache: Boolean = false,
)
