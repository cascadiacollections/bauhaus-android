package com.cascadiacollections.bauhaus.data

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service keys every artwork by UTC date, so [serviceToday] must not drift
 * with the device's zone. These cases pin the two zones where a device-local
 * `LocalDate.now()` is furthest from the service's calendar: Kiritimati is
 * UTC+14, so it is a day ahead of UTC for ten hours out of every twenty-four,
 * and Niue is UTC-11, so it is a day behind for eleven.
 */
class ServiceCalendarTest {

    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `is the UTC date in the default zone`() {
        assertMatchesUtcDate()
    }

    @Test
    fun `is the UTC date well east of UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Pacific/Kiritimati")))

        assertMatchesUtcDate()
    }

    @Test
    fun `is the UTC date well west of UTC`() {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Pacific/Niue")))

        assertMatchesUtcDate()
    }

    /**
     * Brackets the call so that a run straddling midnight UTC — where the date
     * legitimately changes mid-test — is accepted rather than flaking.
     */
    private fun assertMatchesUtcDate() {
        val before = LocalDate.now(ZoneOffset.UTC)
        val actual = serviceToday()
        val after = LocalDate.now(ZoneOffset.UTC)

        assertTrue(
            "serviceToday() returned $actual, outside the UTC window [$before, $after]",
            actual == before || actual == after,
        )
    }
}
