package studio.usergist.feedback.internal.transport

import studio.usergist.feedback.internal.util.Jitter
import kotlin.random.Random

/**
 * Defines when and how often the transport layer retries a failed
 * request, and how long to sleep between attempts.
 *
 * Default: 5 total attempts (1 + 4 retries), exponential full-jitter
 * backoff clamped to 30s. Retries are applied for:
 *
 *  - I/O errors (network errors, DNS failures, timeouts)
 *  - 5xx server responses
 *  - 429 Too Many Requests — the `Retry-After` header, if present,
 *    overrides the computed backoff (seconds or HTTP-date are honored
 *    by callers passing [nextDelayMs]).
 *
 * 4xx responses (other than 429) are terminal.
 */
internal data class RetryPolicy(
    val maxAttempts: Int = 5,
    val baseDelayMs: Long = 500,
    val capDelayMs: Long = 30_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(baseDelayMs > 0) { "baseDelayMs must be > 0" }
        require(capDelayMs >= baseDelayMs) { "capDelayMs must be >= baseDelayMs" }
    }

    /** Whether to retry the given outcome. */
    fun shouldRetry(outcome: Outcome, attempt: Int): Boolean {
        if (attempt + 1 >= maxAttempts) return false
        return when (outcome) {
            is Outcome.IoError -> true
            is Outcome.HttpError -> outcome.status == 429 || outcome.status in 500..599
            is Outcome.Success -> false
        }
    }

    /**
     * Returns the next delay in ms for [attempt] (zero-indexed). If the
     * server provided a `Retry-After` value (parsed to [retryAfterMs]),
     * it wins.
     */
    fun nextDelayMs(
        attempt: Int,
        retryAfterMs: Long? = null,
        random: Random = Random.Default,
    ): Long {
        if (retryAfterMs != null && retryAfterMs >= 0) {
            return retryAfterMs.coerceAtMost(capDelayMs)
        }
        return Jitter.backoffMs(attempt, baseDelayMs, capDelayMs, random)
    }

    /** The outcome of a single HTTP attempt as far as the retry policy cares. */
    sealed class Outcome {
        data object Success : Outcome()
        data class HttpError(val status: Int) : Outcome()
        data class IoError(val cause: Throwable?) : Outcome()
    }
}
