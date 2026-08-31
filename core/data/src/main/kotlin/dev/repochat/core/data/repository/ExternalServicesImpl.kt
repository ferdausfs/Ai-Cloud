package dev.repochat.core.data.repository

import android.util.Base64
import dev.repochat.core.data.remote.CloudflareApi
import dev.repochat.core.data.remote.CloudflareApiErrorDto
import dev.repochat.core.data.remote.FirebaseApi
import dev.repochat.core.data.remote.VercelApi
import dev.repochat.core.data.remote.VercelCreateDeploymentDto
import dev.repochat.core.data.remote.mapHttpErrors
import dev.repochat.core.domain.ExternalServices
import dev.repochat.core.model.AppError
import dev.repochat.core.model.ConnectionType
import dev.repochat.core.model.ServiceConnection
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Read/status implementation for Cloudflare, Vercel and Firebase.
 *
 * All credentials come from the [ServiceConnection] row (never logged). The
 * per-request auth header/query is built here so the OkHttp clients stay
 * reusable and no token is baked into the Retrofit instance.
 */
@Singleton
class ExternalServicesImpl @Inject constructor(
    private val cloudflareApi: CloudflareApi,
    private val vercelApi: VercelApi,
    private val firebaseApi: FirebaseApi,
) : ExternalServices {

    override suspend fun test(connection: ServiceConnection): String = when (connection.type) {
        ConnectionType.CLOUDFLARE,
        ConnectionType.VERCEL,
        ConnectionType.FIREBASE,
        -> summarize(connection)
        else -> throw AppError.Configuration(
            "${connection.label} is not a Cloudflare/Vercel/Firebase connection.",
        )
    }

    override suspend fun summarize(connection: ServiceConnection): String =
        when (connection.type) {
            ConnectionType.CLOUDFLARE -> summarizeCloudflare(connection)
            ConnectionType.VERCEL -> summarizeVercel(connection)
            ConnectionType.FIREBASE -> summarizeFirebase(connection)
            else -> throw AppError.Configuration(
                "${connection.label} does not expose a v1 status endpoint.",
            )
        }

    override suspend fun triggerDeployment(
        connection: ServiceConnection,
        project: String,
    ): String {
        requireVercel(connection)
        val token = connection.apiKey.trim()
        if (token.isBlank()) {
            throw AppError.Configuration("Vercel connection has no API token.")
        }
        val base = connection.baseUrl.trim().trimEnd('/').ifBlank { VERCEL_BASE }
        val team = connection.teamId.trim()
        val url = if (team.isBlank()) "$base/v13/deployments" else "$base/v13/deployments?teamId=$team"
        val name = project.trim().ifBlank { connection.projectId.trim() }
        val body = VercelCreateDeploymentDto(
            name = name.takeIf { it.isNotBlank() },
            target = "production",
            project = name.takeIf { it.isNotBlank() },
        )
        val dto = mapHttpErrors(AppError.Provider.VERCEL) {
            vercelApi.createDeployment(url, "Bearer $token", body)
        }
        if (dto.id.isNullOrBlank()) {
            throw AppError.Api(
                AppError.Provider.VERCEL,
                null,
                "Vercel accepted the request but returned no deployment id.",
            )
        }
        return "Vercel deployment triggered: ${dto.name.ifBlank { dto.id ?: "" }} " +
            "(${dto.state ?: dto.readyState ?: "queued"})${dto.url.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()}"
    }

    /* ------------------------- Cloudflare ------------------------- */

    private suspend fun summarizeCloudflare(connection: ServiceConnection): String {
        val accountId = connection.accountId.trim()
        if (accountId.isBlank()) {
            throw AppError.Configuration("Cloudflare connection needs an Account ID.")
        }
        val token = connection.apiKey.trim()
        if (token.isBlank()) {
            throw AppError.Configuration("Cloudflare connection needs an API token.")
        }
        val base = connection.baseUrl.trim().trimEnd('/').ifBlank { CLOUDFLARE_BASE }
        val bearer = "Bearer $token"
        val zones = mapHttpErrors(AppError.Provider.CLOUDFLARE) {
            cloudflareApi.zones("$base/zones?account.id=$accountId", bearer)
        }
        throwIfCloudflareError(zones.success, zones.errors, "Cloudflare zones")
        val workers = mapHttpErrors(AppError.Provider.CLOUDFLARE) {
            cloudflareApi.workers("$base/accounts/$accountId/workers/scripts", bearer)
        }
        throwIfCloudflareError(workers.success, workers.errors, "Cloudflare Workers")
        return buildString {
            append("Cloudflare OK — ")
            append("${zones.result.size} zone(s)")
            if (zones.result.isNotEmpty()) {
                append(" (${zones.result.first().name})")
            }
            append(", ${workers.result.size} Worker script(s)")
            workers.result.take(3).firstOrNull()?.name?.let { append("; e.g. $it") }
        }
    }

    private fun throwIfCloudflareError(
        success: Boolean,
        errors: List<CloudflareApiErrorDto>,
        what: String,
    ) {
        if (!success) {
            val detail = errors.firstNotNullOfOrNull { it.message?.takeIf(String::isNotBlank) }
                ?: "$what request failed"
            throw AppError.Api(AppError.Provider.CLOUDFLARE, null, detail)
        }
    }

    /* --------------------------- Vercel --------------------------- */

    private suspend fun summarizeVercel(connection: ServiceConnection): String {
        val token = connection.apiKey.trim()
        if (token.isBlank()) {
            throw AppError.Configuration("Vercel connection needs an API token.")
        }
        val base = connection.baseUrl.trim().trimEnd('/').ifBlank { VERCEL_BASE }
        val team = connection.teamId.trim()
        val project = connection.projectId.trim()
        val url = buildString {
            append("$base/v6/deployments")
            val params = mutableListOf<String>()
            if (project.isNotBlank()) params += "projectId=$project"
            if (team.isNotBlank()) params += "teamId=$team"
            if (params.isNotEmpty()) append('?').append(params.joinToString("&"))
        }
        val dto = mapHttpErrors(AppError.Provider.VERCEL) {
            vercelApi.deployments(url, "Bearer $token")
        }
        val latest = dto.deployments.firstOrNull()
        return buildString {
            append("Vercel OK — ")
            if (latest == null) {
                append("no deployments found.")
            } else {
                append("latest deployment: ${latest.name.ifBlank { latest.id ?: "?" }}")
                append(" (${latest.state ?: latest.readyState ?: "unknown"})")
                latest.url.takeIf { it.isNotBlank() }?.let { append(" — $it") }
                append("; total stored: ${dto.deployments.size}")
            }
        }
    }

    private fun requireVercel(connection: ServiceConnection) {
        if (connection.type != ConnectionType.VERCEL) {
            throw AppError.Configuration(
                "Only Vercel connections can trigger a deployment; got ${connection.type}.",
            )
        }
    }

    /* --------------------------- Firebase ------------------------- */

    private suspend fun summarizeFirebase(connection: ServiceConnection): String {
        val projectId = connection.projectId.trim()
        if (projectId.isBlank()) {
            throw AppError.Configuration("Firebase connection needs a Project ID.")
        }
        val base = connection.baseUrl.trim().trimEnd('/').ifBlank { FIREBASE_BASE }
        val key = connection.apiKey.trim()
        val serviceAccount = connection.serviceAccountJson.trim()
        if (key.isBlank() && serviceAccount.isBlank()) {
            throw AppError.Configuration(
                "Firebase connection needs a Web API key or a Service Account JSON.",
            )
        }

        val (headers, projectUrl) = if (serviceAccount.isNotBlank()) {
            val token = exchangeServiceAccountToken(serviceAccount) ?: throw AppError.Api(
                AppError.Provider.FIREBASE,
                null,
                "Firebase OAuth token exchange returned no access token.",
            )
            mapOf("Authorization" to "Bearer $token") to "$base/projects/$projectId"
        } else {
            mapOf<String, String>() to "$base/projects/$projectId?key=$key"
        }

        val dto = mapHttpErrors(AppError.Provider.FIREBASE) {
            firebaseApi.project(projectUrl, headers)
        }
        dto.error?.message?.takeIf { it.isNotBlank() }?.let {
            throw AppError.Api(AppError.Provider.FIREBASE, null, it)
        }
        val displayName = dto.displayName?.takeIf { it.isNotBlank() }
        val number = dto.projectNumber?.takeIf { it.isNotBlank() }
        return buildString {
            append("Firebase OK — project ${dto.projectId.ifBlank { projectId }}")
            displayName?.let { append(" ($it)") }
            number?.let { append(", number $it") }
        }
    }

    /**
     * Exchanges a Service Account JSON for a short-lived OAuth2 access token.
     *
     * This is the more involved step vs. plain bearer-token services: build a
     * signed JWT assertion (RS256), POST it to the token endpoint, then use
     * the returned bearer token against the Firebase Management API.
     */
    private suspend fun exchangeServiceAccountToken(serviceAccountJson: String): String? {
        val sa = FirebaseOAuth.parse(serviceAccountJson)
        if (sa.clientEmail.isBlank() || sa.privateKey.isBlank()) {
            throw AppError.Configuration(
                "Service Account JSON must contain client_email and private_key.",
            )
        }
        val assertion = FirebaseOAuth.buildAssertion(
            clientEmail = sa.clientEmail,
            privateKeyPem = sa.privateKey,
            scope = "https://www.googleapis.com/auth/firebase " +
                "https://www.googleapis.com/auth/cloud-platform",
            tokenUri = sa.tokenUri.ifBlank { GOOGLE_TOKEN_URI },
        )
        val tokenUri = sa.tokenUri.ifBlank { GOOGLE_TOKEN_URI }
        val dto = mapHttpErrors(AppError.Provider.FIREBASE) {
            firebaseApi.oauthToken(
                url = tokenUri,
                grantType = "urn:ietf:params:oauth:grant-type:jwt-bearer",
                assertion = assertion,
            )
        }
        dto.error?.takeIf { it.isNotBlank() }?.let {
            throw AppError.Api(
                AppError.Provider.FIREBASE,
                null,
                dto.errorDescription?.takeIf(String::isNotBlank) ?: it,
            )
        }
        return dto.accessToken?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val CLOUDFLARE_BASE = "https://api.cloudflare.com/client/v4"
        const val VERCEL_BASE = "https://api.vercel.com"
        const val FIREBASE_BASE = "https://firebase.googleapis.com/v1beta1"
        const val GOOGLE_TOKEN_URI = "https://oauth2.googleapis.com/token"
    }
}

/**
 * Pure-JVM helpers to build and sign the JWT assertion used by the Firebase
 * Service Account OAuth2 flow. Uses [android.util.Base64] so it runs on
 * Android (API 24+ has no java.util.Base64).
 */
internal object FirebaseOAuth {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        // JwtHeader has all-default fields (alg/typ); without this the encoded
        // JSON would be `{}` and the OAuth server would reject the assertion.
        encodeDefaults = true
    }

    @Serializable
    private data class ServiceAccountPayload(
        @SerialName("client_email") val clientEmail: String = "",
        @SerialName("private_key") val privateKey: String = "",
        @SerialName("token_uri") val tokenUri: String = "",
    )

    @Serializable
    private data class JwtHeader(val alg: String = "RS256", val typ: String = "JWT")

    @Serializable
    private data class JwtClaims(
        val iss: String,
        val scope: String,
        val aud: String,
        val iat: Long,
        val exp: Long,
    )

    data class ParsedServiceAccount(
        val clientEmail: String,
        val privateKey: String,
        val tokenUri: String,
    )

    fun parse(raw: String): ParsedServiceAccount {
        val dto = json.decodeFromString(ServiceAccountPayload.serializer(), raw)
        return ParsedServiceAccount(
            clientEmail = dto.clientEmail.trim(),
            privateKey = dto.privateKey.trim(),
            tokenUri = dto.tokenUri.trim(),
        )
    }

    fun buildAssertion(
        clientEmail: String,
        privateKeyPem: String,
        scope: String,
        tokenUri: String,
        nowMillis: Long = System.currentTimeMillis(),
        base64UrlEncode: (ByteArray) -> String = ::base64UrlNoPad,
        base64Decode: (String) -> ByteArray = ::base64PemDecode,
    ): String {
        val iat = nowMillis / 1000L
        val exp = iat + 3600L
        val header = base64UrlEncode(
            json.encodeToString(JwtHeader.serializer(), JwtHeader()).toByteArray(Charsets.UTF_8),
        )
        val claims = base64UrlEncode(
            json.encodeToString(
                JwtClaims.serializer(),
                JwtClaims(iss = clientEmail, scope = scope, aud = tokenUri, iat = iat, exp = exp),
            ).toByteArray(Charsets.UTF_8),
        )
        val unsigned = "$header.$claims"
        val signature = signRsa256(unsigned, privateKeyPem, base64UrlEncode, base64Decode)
        return "$unsigned.$signature"
    }

    private fun signRsa256(
        input: String,
        privateKeyPem: String,
        base64UrlEncode: (ByteArray) -> String,
        base64Decode: (String) -> ByteArray,
    ): String {
        val key = parsePrivateKey(privateKeyPem, base64Decode)
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(key)
        signature.update(input.toByteArray(Charsets.US_ASCII))
        return base64UrlEncode(signature.sign())
    }

    /**
     * [base64Decode] decodes the standard (non-URL) base64 in a PKCS#8 PEM
     * body (which may contain `+` and `/`). Injectable so a pure-JVM test can
     * use java.util.Base64 instead of [android.util.Base64].
     */
    private fun parsePrivateKey(
        pem: String,
        base64Decode: (String) -> ByteArray,
    ): PrivateKey {
        val body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace(Regex("""\s"""), "")
        val der = base64Decode(body)
        val keySpec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    private fun base64UrlNoPad(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun base64PemDecode(value: String): ByteArray =
        Base64.decode(value, Base64.DEFAULT)
}
