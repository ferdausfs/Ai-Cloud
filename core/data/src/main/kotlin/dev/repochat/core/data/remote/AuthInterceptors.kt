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
 */
internal class OllamaAuthInterceptor(
    private val apiKey: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val key = apiKey()
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
