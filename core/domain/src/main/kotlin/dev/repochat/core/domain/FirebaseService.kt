package dev.repochat.core.domain

import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionResult
import dev.repochat.core.model.FirebaseProjectInfo
import dev.repochat.core.model.FirebaseServiceInfo

/** Firebase REST API surface (v1). Read-only project/service status and simple
 * Firestore document reads using a Web API Key.
 *
 * Admin-level actions (writes, admin SDK operations) require a service account
 * JSON + an OAuth2 token-exchange step (sign JWT → exchange for access token).
 * That exchange is NOT implemented here — it is multi-step, needs private key
 * signing, and is out of scope for a phone app's first integration. When a
 * service account is configured the app notes the limitation in the UI rather
 * than attempting the exchange.
 */
interface FirebaseService {
    suspend fun test(apiKey: String, projectId: String, serviceAccountJson: String?): ConnectionResult
    suspend fun getProject(apiKey: String, projectId: String): FirebaseProjectInfo
    suspend fun listServices(apiKey: String, projectId: String): List<FirebaseServiceInfo>
}
