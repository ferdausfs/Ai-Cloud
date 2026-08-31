package dev.repochat.core.domain

import dev.repochat.core.model.ServiceConnection

/**
 * Read/status surface for the non-LLM service connections (Cloudflare,
 * Vercel, Firebase) that Settings exposes as typed credential rows.
 *
 * v1 scope:
 * - Cloudflare: read-only status endpoints (zones + Workers script status).
 *   Deploying Workers requires wrangler/CI, so the app never triggers a
 *   Workers deploy — that is intentionally out of scope from the phone.
 * - Vercel: read (deployment status) and write (trigger a new deployment).
 * - Firebase: read-only project config via a Web API key, or admin-level
 *   calls via a Service Account JSON (requires the OAuth2 token-exchange
 *   step implemented by the data layer).
 */
interface ExternalServices {

    /** One-shot validate credential + summarize what is reachable. */
    suspend fun test(connection: ServiceConnection): String

    /**
     * Read status for the connection and return a plain-language summary
     * (zones/Workers counts, latest Vercel deployment, Firebase project).
     */
    suspend fun summarize(connection: ServiceConnection): String

    /** Vercel only: trigger a new deployment. Throws for other connect types. */
    suspend fun triggerDeployment(connection: ServiceConnection, project: String): String
}
