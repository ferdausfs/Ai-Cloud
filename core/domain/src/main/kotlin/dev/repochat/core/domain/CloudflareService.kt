package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionResult
import dev.repochat.core.model.CloudflareAccountInfo
import dev.repochat.core.model.CloudflareZoneInfo
import dev.repochat.core.model.CloudflareWorkerScriptInfo

/** Read-only Cloudflare surface: accounts, zones, Workers script status.
 * Deploy triggers are out of scope for v1 (needs `wrangler`/CI). */
interface CloudflareService {
    suspend fun test(apiKey: String, accountId: String?): ConnectionResult
    suspend fun listAccounts(apiKey: String): List<CloudflareAccountInfo>
    suspend fun listZones(apiKey: String, accountId: String): List<CloudflareZoneInfo>
    suspend fun listWorkersScripts(apiKey: String, accountId: String): List<CloudflareWorkerScriptInfo>
}
