package studio.usergist.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.usergist.feedback.internal.util.DateTime

class DateTimeTest {
    @Test
    fun timestampsStayIso8601AndApi24Safe() {
        val value = DateTime.nowIso()

        assertTrue(value.endsWith("Z"))
        assertNotNull(DateTime.parseIso(value))
    }

    @Test
    fun surveyDatesAreStrictAndRoundTrip() {
        val leapDay = DateTime.parseLocalDate("2024-02-29")

        assertEquals("2024-02-29", leapDay?.iso)
        assertNull(DateTime.parseLocalDate("2023-02-29"))
        assertNull(DateTime.parseLocalDate("2024-2-9"))
        assertEquals("2026-08-26", DateTime.localDate(2026, 7, 26)?.iso)
    }
}
