package studio.usergist.feedback.internal.util

import kotlin.random.Random

/**
 * Exponential-backoff with full jitter helpers.
 *
 * Reference: https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/
 */
internal object Jitter {

    /**
     * Returns a delay in milliseconds for the given zero-indexed [attempt]
     * using "full jitter": `rand(0, min(cap, base * 2^attempt))`.
     */
    fun backoffMs(
        attempt: Int,
        baseMs: Long = 500,
        capMs: Long = 30_000,
        random: Random = Random.Default,
    ): Long {
        require(attempt >= 0) { "attempt must be non-negative" }
        // Cap the exponent to avoid overflow.
        val exp = attempt.coerceAtMost(16)
        val expDelay = baseMs shl exp
        val ceiling = expDelay.coerceAtMost(capMs).coerceAtLeast(1)
        return random.nextLong(0, ceiling + 1)
    }
}
