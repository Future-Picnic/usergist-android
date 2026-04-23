package studio.ritmus.feedback.internal

import android.util.Log

/**
 * Thin wrapper around [android.util.Log] that respects the SDK's runtime
 * `debug` flag.
 *
 * In release builds with `debug=false`, only warnings and errors are
 * emitted to logcat. Debug traces give developers a decision log per
 * DEV_PRD §13 without leaking noise in production.
 */
internal object RitmusLogger {

    private const val TAG: String = "Ritmus"

    @Volatile
    private var debugEnabled: Boolean = false

    /** Enables or disables debug-level logging. Safe to toggle at runtime. */
    fun setDebug(enabled: Boolean) {
        debugEnabled = enabled
    }

    fun d(message: String) {
        if (debugEnabled) {
            Log.d(TAG, message)
        }
    }

    fun d(message: String, error: Throwable?) {
        if (debugEnabled) {
            Log.d(TAG, message, error)
        }
    }

    fun w(message: String) {
        Log.w(TAG, message)
    }

    fun w(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
    }
}
