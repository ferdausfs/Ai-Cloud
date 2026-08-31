package dev.repochat.core.data.remote

import kotlinx.serialization.Serializable

/** Firebase REST API surface for v1. The Firebase REST APIs (Firestore, Realtime
 * Database, Remote Config, etc.) are served from `firebaseio.com` / `firestore`
 * hosts and accept either a Web API Key (simple config/document reads) or an
 * OAuth2 access token obtained from a service account (admin-level actions).
 *
 * v1 scope is read-only status/config reads. Writes and admin actions require a
 * full service account JSON + a token-exchange step (see [FirebaseService] notes).
 * This interface deliberately does not implement token exchange — that is a
 * multi-step OAuth2 flow requiring private key signing, out of scope for a phone
 * app's first integration.
 */
interface FirebaseApi {

    /** GET /v1/projects/{projectId} — project metadata (name, projectNumber,
     * defaultRegion, etc.). Accepts Web API Key as `key=` query param. */
    @retrofit2.http.GET("v1/projects/{projectId}")
    suspend fun getProject(
        @retrofit2.http.Path("projectId") projectId: String,
        @retrofit2.http.Query("key") apiKey: String,
    ): FirebaseProjectDto

    /** GET /v1/projects/{projectId}/services — list enabled Firebase services
     * (e.g. firestore, auth, hosting) and their status. Accepts Web API Key. */
    @retrofit2.http.GET("v1/projects/{projectId}/services")
    suspend fun listServices(
        @retrofit2.http.Path("projectId") projectId: String,
        @retrofit2.http.Query("key") apiKey: String,
    ): FirebaseServicesDto

    /** GET /v1/{database}/documents/{collection}/{document} — a single Firestore
     * document read using the Web API Key path. For v1 this is the simplest
     * "did my config reach the server" probe; real apps extend this per use case.
     *
     * When a service account is available the caller should exchange it for an
     * OAuth2 token first and pass `Authorization: Bearer <token>` via headers
     * instead of the `key` query param — but that exchange is not implemented
     * here (see class doc).
     */
    @retrofit2.http.GET
    suspend fun readFirestoreDocument(
        @retrofit2.http.Url url: String,
        @retrofit2.http.Query("key") apiKey: String? = null,
        @retrofit2.http.HeaderMap headers: Map<String, String> = emptyMap(),
    ): FirebaseDocumentDto
}

/* ---------------------------- DTOs ---------------------------- */

@Serializable
data class FirebaseProjectDto(
    val name: String = "",
    val projectId: String = "",
    val projectNumber: String = "",
    val defaultCluster: String? = null,
)

@Serializable
data class FirebaseServicesDto(
    val services: List<FirebaseServiceDto> = emptyList(),
)

@Serializable
data class FirebaseServiceDto(
    val name: String = "",
    val state: String = "",
    val disabledPolicies: List<String> = emptyList(),
)

@Serializable
data class FirebaseDocumentDto(
    val name: String = "",
    val fields: Map<String, FirebaseValueDto>? = null,
)

/** Firestore values are typed wrappers; keep it minimal for v1. */
@Serializable
data class FirebaseValueDto(
    val stringValue: String? = null,
    val integerValue: String? = null,
    val booleanValue: Boolean? = null,
    val doubleValue: String? = null,
    val timestampValue: String? = null,
    val geoPointValue: FirebaseGeoPointDto? = null,
    val arrayValue: FirebaseArrayDto? = null,
    val mapValue: FirebaseMapDto? = null,
)

@Serializable
data class FirebaseGeoPointDto(val latitude: Double = 0.0, val longitude: Double = 0.0)

@Serializable
data class FirebaseArrayDto(val values: List<FirebaseValueDto> = emptyList())

@Serializable
data class FirebaseMapDto(val fields: Map<String, FirebaseValueDto> = emptyMap())
