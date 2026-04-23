package studio.ritmus.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.ritmus.feedback.internal.transport.RetryPolicy
import kotlin.random.Random

class RetryPolicyTest {

    @Test
    fun retries_5xx_and_ioerror_until_max() {
        val policy = RetryPolicy(maxAttempts = 5)
        val http500 = RetryPolicy.Outcome.HttpError(500)
        val io = RetryPolicy.Outcome.IoError(RuntimeException("boom"))

        assertTrue(policy.shouldRetry(http500, 0))
        assertTrue(policy.shouldRetry(io, 0))
        assertTrue(policy.shouldRetry(http500, 3))
        // attempt 4 is the 5th try — no retry after.
        assertFalse(policy.shouldRetry(http500, 4))
    }

    @Test
    fun does_not_retry_4xx_except_429() {
        val policy = RetryPolicy()
        assertFalse(policy.shouldRetry(RetryPolicy.Outcome.HttpError(400), 0))
        assertFalse(policy.shouldRetry(RetryPolicy.Outcome.HttpError(404), 0))
        assertTrue(policy.shouldRetry(RetryPolicy.Outcome.HttpError(429), 0))
    }

    @Test
    fun backoff_uses_full_jitter_within_bounds() {
        val policy = RetryPolicy(baseDelayMs = 500, capDelayMs = 30_000)
        // Bucket for attempt=2: base << 2 = 2000ms
        val r = Random(42)
        val delay = policy.nextDelayMs(attempt = 2, random = r)
        assertTrue("delay=$delay", delay in 0..2000)
    }

    @Test
    fun backoff_clamps_at_cap() {
        val policy = RetryPolicy(baseDelayMs = 500, capDelayMs = 5_000)
        val delay = policy.nextDelayMs(attempt = 10, random = Random(1))
        assertTrue("delay=$delay should be <= cap", delay <= 5_000)
    }

    @Test
    fun retry_after_overrides_backoff() {
        val policy = RetryPolicy(capDelayMs = 60_000)
        val delay = policy.nextDelayMs(attempt = 0, retryAfterMs = 10_000, random = Random(1))
        assertEquals(10_000L, delay)
    }

    @Test
    fun retry_after_is_clamped_to_cap() {
        val policy = RetryPolicy(capDelayMs = 10_000)
        val delay = policy.nextDelayMs(attempt = 0, retryAfterMs = 60_000, random = Random(1))
        assertEquals(10_000L, delay)
    }
}
