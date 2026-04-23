package studio.ritmus.feedback.internal.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tiny ISO-8601 helper with UTC + millisecond precision. */
internal object DateTime {

    /** Returns the current time as an ISO-8601 string, e.g. `2026-04-21T12:34:56.789Z`. */
    fun nowIso(): String = isoFormat().format(Date())

    /** Returns `epochMillis` as an ISO-8601 UTC string. */
    fun isoFromMillis(epochMillis: Long): String = isoFormat().format(Date(epochMillis))

    /**
     * Parses an ISO-8601 string, returning epoch millis, or `null` on
     * failure. Accepts a trailing `Z` or a `±HH:MM` offset.
     */
    fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            isoFormat().parse(value)?.time
        } catch (e: Throwable) {
            // `SimpleDateFormat` can throw for various formats — fall back
            // to a more lenient variant without millis.
            try {
                lenientIsoFormat().parse(value)?.time
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun isoFormat(): SimpleDateFormat {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt
    }

    private fun lenientIsoFormat(): SimpleDateFormat {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt
    }
}
