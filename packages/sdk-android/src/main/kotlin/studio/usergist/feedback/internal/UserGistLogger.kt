package studio.usergist.feedback.internal

import android.util.Log

/**
 * Thin wrapper around [android.util.Log] that respects the SDK's runtime
 * `debug` flag.
 *
 * In release builds with `debug=false`, only warnings and errors are
 * emitted to logcat. Debug traces give developers a decision log per
 * DEV_PRD §13 without leaking noise in production.
 */
internal object UserGistLogger {

    private const val TAG: String = "UserGist"

    @Volatile
    private var debugEnabled: Boolean = false

    @Volatile
    private var diagnosticHandler: ((String) -> Unit)? = null

    fun setDiagnosticHandler(handler: ((String) -> Unit)?) {
        diagnosticHandler = handler
    }

    private fun diagnostic(message: String) {
        try {
            diagnosticHandler?.invoke(message.take(200))
        } catch (_: Throwable) {
            // Host diagnostics must never cross the SDK boundary.
        }
    }

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
        diagnostic(message)
        Log.w(TAG, message)
    }

    fun w(message: String, error: Throwable?) {
        diagnostic(message)
        Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable?) {
        diagnostic(message)
        Log.e(TAG, message, error)
    }
}
