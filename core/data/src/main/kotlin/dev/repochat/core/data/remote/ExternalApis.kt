package dev.repochat.core.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cloudflare API v4 (read-only for v1).
 *
 * NOTE: v1 deliberately does NOT trigger Workers deploys from the phone —
 * that needs `wrangler`/CI, which is out of scope. The app only lists zones
 * and the account's Worker scripts to prove the token + account id work.
 */
interface CloudflareApi {

    @retrofit2.http.GET
    suspend fun zones(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") bearer: String,
    ): CloudflareZonesDto

    @retrofit2.http.GET
    suspend fun workers(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") bearer: String,
    ): CloudflareWorkersDto
}

/** Vercel REST API. Read deployment status + trigger a new deployment. */
interface VercelApi {

    @retrofit2.http.GET
    suspend fun deployments(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") bearer: String,
    ): VercelDeploymentsDto

    @retrofit2.http.POST
    suspend fun createDeployment(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Header("Authorization") bearer: String,
        @retrofit2.http.Body body: VercelCreateDeploymentDto,
    ): VercelDeploymentDto
}

/** Firebase REST surface used for v1 read status + OAuth2 token exchange. */
interface FirebaseApi {

    @retrofit2.http.GET
    suspend fun project(
        @retrofit2.http.Url url: String,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): FirebaseProjectDto

    @retrofit2.http.POST
    @retrofit2.http.FormUrlEncoded
    suspend fun oauthToken(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Field("grant_type") grantType: String,
        @retrofit2.http.Field("assertion") assertion: String,
    ): FirebaseTokenDto
}

/* ---------------------------- DTOs ---------------------------- */

@Serializable
data class CloudflareZonesDto(
    val success: Boolean = false,
    val result: List<CloudflareZoneDto> = emptyList(),
    val errors: List<CloudflareApiErrorDto> = emptyList(),
)

@Serializable
data class CloudflareZoneDto(
    val id: String = "",
    val name: String = "",
    val status: String? = null,
)

@Serializable
data class CloudflareWorkersDto(
    val success: Boolean = false,
    val result: List<CloudflareWorkerDto> = emptyList(),
    val errors: List<CloudflareApiErrorDto> = emptyList(),
)

@Serializable
data class CloudflareWorkerDto(
    val id: String = "",
    val name: String? = null,
    @SerialName("modified_on") val modifiedOn: String? = null,
)

@Serializable
data class CloudflareApiErrorDto(val message: String? = null)

/** Cloudflare non-2xx body: `{"success":false,"errors":[{"message":…}]}`. */
@Serializable
data class CloudflareErrorBodyDto(
    val success: Boolean = false,
    val errors: List<CloudflareApiErrorDto> = emptyList(),
    val message: String? = null,
)

/** Vercel non-2xx body: `{"error":{"message":…}}` or `{"message":…}`. */
@Serializable
data class VercelErrorWrapperDto(
    val error: VercelApiErrorDto? = null,
    val message: String? = null,
)

/**
 * Firebase/Google errors come in two shapes:
 * `{"error":{"message":…}}` (REST) and `{"error":"invalid_grant","error_description":…}`
 * (OAuth token). [error] is parsed as a flexible [kotlinx.serialization.json.JsonElement].
 */
@Serializable
data class FirebaseApiErrorDto(
    val error: kotlinx.serialization.json.JsonElement? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val message: String? = null,
)

@Serializable
data class VercelDeploymentsDto(
    val deployments: List<VercelDeploymentDto> = emptyList(),
    val error: VercelApiErrorDto? = null,
)

@Serializable
data class VercelDeploymentDto(
    val id: String? = null,
    val name: String = "",
    val state: String? = null,
    val url: String = "",
    @SerialName("created") val createdAt: Long? = null,
    @SerialName("readyState") val readyState: String? = null,
    val project: String? = null,
)

@Serializable
data class VercelCreateDeploymentDto(
    val name: String? = null,
    val target: String? = null,
    val project: String? = null,
)

@Serializable
data class VercelApiErrorDto(val message: String? = null, val code: String? = null)

@Serializable
data class FirebaseProjectDto(
    @SerialName("projectId") val projectId: String = "",
    @SerialName("displayName") val displayName: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("projectNumber") val projectNumber: String? = null,
    val error: FirebaseApiErrorBody? = null,
)

@Serializable
data class FirebaseTokenDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

@Serializable
data class FirebaseApiErrorBody(
    val message: String? = null,
    val status: String? = null,
    val code: String? = null,
)
