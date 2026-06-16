package io.github.lycheeappf.tmm.channel.llm.provider.grok

import io.github.lycheeappf.tmm.core.util.LogBuffer
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp-Interceptor, der defensiv loggt: nur Methode, URL und Statuscode —
 * niemals Authorization-Header, niemals Request- oder Response-Body. Damit
 * landen Diktate, System-Prompts und API-Keys auch unter `Log.DEBUG` nie im
 * Logcat, geschweige denn im exportierbaren [LogBuffer].
 */
@Singleton
class RedactingAuthInterceptor @Inject constructor(
    private val logBuffer: LogBuffer
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            logBuffer.warn(
                TAG,
                "HTTP ${request.method} ${request.url.encodedPath} failed after ${elapsedMs}ms: " +
                    (e.message ?: e::class.simpleName.orEmpty())
            )
            throw e
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        logBuffer.info(
            TAG,
            "HTTP ${request.method} ${request.url.encodedPath} → ${response.code} (${elapsedMs}ms)"
        )
        return response
    }

    companion object {
        private const val TAG = "GrokHttp"
    }
}
