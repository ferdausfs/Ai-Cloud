package dev.repochat.core.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Attaches the user's GitHub PAT to every request at call time, so a token
 * changed in Settings applies immediately without rebuilding the client.
 */
internal class GithubAuthInterceptor(
    private val pat: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = pat()
        val request = if (token.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "token $token")
                .build()
        }
        return chain.proceed(request)
    }
}

/**
 * Attaches the Ollama Cloud API key (Bearer) at call time.
 * [OllamaKeyOverride] lets a per-connection call temporarily replace the
 * default Settings key (used by the multi-provider router).
 */
internal class OllamaAuthInterceptor(
    private val apiKey: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = OllamaKeyOverride.current() ?: apiKey()
        val request = if (key.isBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $key")
                .build()
        }
        return chain.proceed(request)
    }
}

/** Process-local override for the next Ollama call (single-threaded coroutine use). */
object OllamaKeyOverride {
    @Volatile
    private var override: String? = null

    fun current(): String? = override

    fun <T> withKey(key: String?, block: () -> T): T {
        val prev = override
        override = key
        return try {
            block()
        } finally {
            override = prev
        }
    }

    suspend fun <T> withKeySuspend(key: String?, block: suspend () -> T): T {
        val prev = override
        override = key
        return try {
            block()
        } finally {
            override = prev
        }
    }
}
