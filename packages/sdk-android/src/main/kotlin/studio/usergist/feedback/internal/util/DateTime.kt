package studio.usergist.feedback.internal.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Tiny ISO-8601 helper with UTC + millisecond precision. */
internal object DateTime {

    /** API-24-safe representation of the survey wire format `yyyy-MM-dd`. */
    data class LocalDateValue(
        val year: Int,
        val month: Int,
        val day: Int,
    ) {
        val iso: String
            get() = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

        fun utcStartMillis(): Long = calendarUtc().timeInMillis

        private fun calendarUtc(): Calendar = Calendar.getInstance(UTC).apply {
            clear()
            isLenient = false
            set(year, month - 1, day, 0, 0, 0)
        }
    }

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

    /** Parses the strict survey date wire format without requiring API 26. */
    fun parseLocalDate(value: String?): LocalDateValue? {
        if (value == null || !LOCAL_DATE.matches(value)) return null
        val parts = value.split('-')
        val date = LocalDateValue(
            year = parts[0].toInt(),
            month = parts[1].toInt(),
            day = parts[2].toInt(),
        )
        return runCatching {
            date.utcStartMillis()
            date
        }.getOrNull()
    }

    /** Creates a validated survey date from Android's zero-based picker month. */
    fun localDate(year: Int, monthZeroBased: Int, day: Int): LocalDateValue? {
        val date = LocalDateValue(year, monthZeroBased + 1, day)
        return runCatching {
            date.utcStartMillis()
            date
        }.getOrNull()
    }

    /** Returns today's local calendar date, matching `LocalDate.now()` semantics. */
    fun todayLocalDate(): LocalDateValue {
        val calendar = Calendar.getInstance()
        return LocalDateValue(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun isoFormat(): SimpleDateFormat {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        fmt.timeZone = UTC
        fmt.isLenient = false
        return fmt
    }

    private fun lenientIsoFormat(): SimpleDateFormat {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        fmt.timeZone = UTC
        fmt.isLenient = false
        return fmt
    }

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    private val LOCAL_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
}
