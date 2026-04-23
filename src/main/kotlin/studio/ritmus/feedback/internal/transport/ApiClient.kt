package studio.ritmus.feedback.internal.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import studio.ritmus.feedback.internal.BuildConfigProxy
import studio.ritmus.feedback.internal.RitmusLogger
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thin HTTPS client for the SDK's five endpoints.
 *
 * Responsibilities:
 *  - serialize JSON payloads via kotlinx.serialization
 *  - attach `Authorization: Bearer <writeKey>` and SDK identity headers
 *  - execute requests on [Dispatchers.IO]
 *  - apply the [RetryPolicy] (exponential backoff with jitter, 5 attempts,
 *    respects `Retry-After`)
 */
internal class ApiClient(
    private val baseUrl: String,
    private val writeKey: String,
    private val json: Json,
    private val client: OkHttpClient = defaultClient(),
    private val policy: RetryPolicy = RetryPolicy(),
) {

    /**
     * Executes a POST with a serializable payload. Returns `true` iff the
     * request ultimately succeeded (HTTP 2xx).
     */
    suspend fun <T> postJson(
        path: String,
        body: T,
        serializer: SerializationStrategy<T>,
    ): Boolean = withContext(Dispatchers.IO) {
        val encoded = try {
            json.encodeToString(serializer, body)
        } catch (e: Throwable) {
            RitmusLogger.w("ApiClient.postJson encode failed for $path", e)
            return@withContext false
        }
        val requestBody = encoded.toRequestBody(JSON_MEDIA_TYPE)
        val request = baseRequestBuilder(path)
            .post(requestBody)
            .build()
        executeWithRetry(request).first
    }

    /**
     * Executes a GET with query params and parses the response body via [deserializer].
     * Returns the parsed value or `null` on terminal failure.
     */
    suspend fun <T> getJson(
        path: String,
        query: Map<String, String?>,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): T? = withContext(Dispatchers.IO) {
        val url = buildUrl(path, query) ?: return@withContext null
        val request = baseRequestBuilder(url).get().build()
        val (success, body) = executeWithRetry(request)
        if (!success || body == null) return@withContext null
        try {
            json.decodeFromString(deserializer, body)
        } catch (e: Throwable) {
            RitmusLogger.w("ApiClient.getJson decode failed for $path", e)
            null
        }
    }

    // ---------------- Internal ----------------

    private suspend fun executeWithRetry(request: Request): Pair<Boolean, String?> {
        var attempt = 0
        while (true) {
            val (outcome, body, retryAfterMs) = try {
                client.newCall(request).execute().use { response ->
                    val bodyText = runCatching { response.body?.string() }.getOrNull()
                    val out = when {
                        response.isSuccessful -> RetryPolicy.Outcome.Success
                        else -> RetryPolicy.Outcome.HttpError(response.code)
                    }
                    Triple(out, bodyText, parseRetryAfter(response.headers))
                }
            } catch (e: IOException) {
                Triple(RetryPolicy.Outcome.IoError(e), null, null)
            } catch (e: Throwable) {
                // Anything else is unexpected — treat as terminal, log it.
                RitmusLogger.w("ApiClient unexpected error for ${request.url}", e)
                Triple(RetryPolicy.Outcome.IoError(e), null, null)
            }

            if (outcome is RetryPolicy.Outcome.Success) {
                return true to body
            }

            if (!policy.shouldRetry(outcome, attempt)) {
                when (outcome) {
                    is RetryPolicy.Outcome.HttpError ->
                        RitmusLogger.w("ApiClient terminal HTTP ${outcome.status} for ${request.url}")
                    is RetryPolicy.Outcome.IoError ->
                        RitmusLogger.w("ApiClient terminal IO error for ${request.url}", outcome.cause)
                    RetryPolicy.Outcome.Success -> Unit
                }
                return false to body
            }

            val sleepMs = policy.nextDelayMs(attempt, retryAfterMs)
            RitmusLogger.d("ApiClient retry attempt ${attempt + 1} after ${sleepMs}ms for ${request.url}")
            delay(sleepMs)
            attempt += 1
        }
    }

    private fun baseRequestBuilder(path: String): Request.Builder {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            stripTrailingSlash(baseUrl) + path
        }
        return Request.Builder()
            .url(url)
            .headers(defaultHeaders())
    }

    private fun baseRequestBuilder(url: String): Request.Builder =
        Request.Builder().url(url).headers(defaultHeaders())

    private fun defaultHeaders(): Headers = Headers.Builder()
        .add("Authorization", "Bearer $writeKey")
        .add("Content-Type", "application/json")
        .add("Accept", "application/json")
        .add("X-Ritmus-Sdk", "android/${BuildConfigProxy.SDK_VERSION}")
        .build()

    private fun buildUrl(path: String, query: Map<String, String?>): String? = try {
        val base = stripTrailingSlash(baseUrl) + path
        val builder = base.toHttpUrl().newBuilder()
        for ((k, v) in query) {
            if (!v.isNullOrBlank()) builder.addQueryParameter(k, v)
        }
        builder.build().toString()
    } catch (e: Throwable) {
        RitmusLogger.w("ApiClient.buildUrl failed for $path", e)
        null
    }

    private fun stripTrailingSlash(url: String): String =
        if (url.endsWith("/")) url.dropLast(1) else url

    private fun parseRetryAfter(headers: Headers): Long? {
        val value = headers["Retry-After"] ?: headers["retry-after"] ?: return null
        val trimmed = value.trim()
        // First try an integer number of seconds.
        trimmed.toLongOrNull()?.let { return it * 1000 }
        // Fall back to HTTP-date (RFC 7231 §7.1.3).
        return try {
            val parser = java.text.SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                java.util.Locale.US,
            ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            val date = parser.parse(trimmed) ?: return null
            (date.time - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Reasonable defaults for SDK-sized payloads. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // handled by our RetryPolicy
            .build()
    }
}

/** A tiny indirection so the client never touches generated `BuildConfig` directly. */
internal object BuildConfigProxy {
    val SDK_VERSION: String
        get() = try {
            studio.ritmus.feedback.BuildConfig.SDK_VERSION
        } catch (_: Throwable) {
            "0.1.0"
        }
}
