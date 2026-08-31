package dev.repochat.core.data.remote

import kotlinx.serialization.Serializable

/** Cloudflare REST API (api.cloudflare.com) — read-only v1 endpoints for the
 * app: account/zones listing and Workers script status. Deploy triggers are
 * intentionally out of scope for v1 (needs `wrangler`/CI on a real machine).
 */
interface CloudflareApi {

    /** GET /accounts — list accounts the token can access. */
    @retrofit2.http.GET("accounts")
    suspend fun listAccounts(): CloudflareAccountsDto

    /** GET /accounts/{accountId}/zones — list zones for an account. */
    @retrofit2.http.GET
    suspend fun listZones(
        @retrofit2.http.Url url: String,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): CloudflareZonesDto

    /** GET /accounts/{accountId}/workers/scripts — list Workers scripts
     * (light status probe; deploy triggers are out of scope for v1). */
    @retrofit2.http.GET
    suspend fun listWorkersScripts(
        @retrofit2.http.Url url: String,
        @retrofit2.http.HeaderMap headers: Map<String, String>,
    ): CloudflareWorkersScriptsDto
}

/* ---------------------------- DTOs ---------------------------- */

@Serializable
data class CloudflareAccountsDto(val result: List<CloudflareAccountDto> = emptyList())

@Serializable
data class CloudflareAccountDto(
    val id: String = "",
    val email: String = "",
    val name: String = "",
)

@Serializable
data class CloudflareZonesDto(val result: List<CloudflareZoneDto> = emptyList())

@Serializable
data class CloudflareZoneDto(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    val plan: CloudflarePlanDto? = null,
)

@Serializable
data class CloudflarePlanDto(val name: String = "")

@Serializable
data class CloudflareWorkersScriptsDto(
    val result: List<CloudflareWorkerScriptDto> = emptyList(),
)

@Serializable
data class CloudflareWorkerScriptDto(
    val id: String = "",
    val name: String = "",
    val etag: String = "",
)
