package studio.usergist.feedback.internal

import studio.usergist.feedback.api.Environment

/**
 * Fully-resolved SDK configuration captured at `initialize()` time.
 *
 * Kept immutable — callers that want to change behaviour must call
 * [studio.usergist.feedback.UserGist.initialize] again (idempotent).
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
        internal const val SDK_VERSION_FALLBACK: String = "0.1.4"
        /** Exposed to other packages (e.g. push) without needing a config instance. */
        internal const val SDK_VERSION: String = SDK_VERSION_FALLBACK
    }
}
