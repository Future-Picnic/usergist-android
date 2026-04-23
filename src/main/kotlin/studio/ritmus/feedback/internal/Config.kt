package studio.ritmus.feedback.internal

import studio.ritmus.feedback.api.Environment

/**
 * Fully-resolved SDK configuration captured at `initialize()` time.
 *
 * Kept immutable — callers that want to change behaviour must call
 * [studio.ritmus.feedback.Ritmus.initialize] again (idempotent).
 */
internal data class Config(
    val writeKey: String,
    val environment: Environment,
    val apiUrl: String,
    val debug: Boolean,
    val flushIntervalMs: Long,
    val flushBatchSize: Int,
    val maxQueueSize: Int,
    val triggerSyncIntervalMs: Long,
) {
    companion object {
        internal const val SDK_PLATFORM: String = "android"
        internal const val SDK_VERSION_FALLBACK: String = "0.1.0"
    }
}
