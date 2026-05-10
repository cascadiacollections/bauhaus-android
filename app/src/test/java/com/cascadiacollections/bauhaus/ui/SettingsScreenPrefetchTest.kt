package com.cascadiacollections.bauhaus.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SettingsScreenPrefetchTest {

    private val today = LocalDate.of(2026, 5, 10)

    @Test
    fun `neighborPrefetchRequests returns previous and next for middle page`() {
        val dates = listOf(today, today.minusDays(1), today.minusDays(2))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 1,
            today = today,
            imageRevision = 3,
        )

        assertEquals(
            listOf(
                ArchiveImageRequest("/api/today", "2026-05-10-3"),
                ArchiveImageRequest("/api/2026-05-08", "2026-05-08-3"),
            ),
            requests,
        )
    }

    @Test
    fun `neighborPrefetchRequests returns only one neighbor at edges`() {
        val dates = listOf(today, today.minusDays(1))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 0,
            today = today,
            imageRevision = 1,
        )

        assertEquals(listOf(ArchiveImageRequest("/api/2026-05-09", "2026-05-09-1")), requests)
    }

    @Test
    fun `neighborPrefetchRequests returns empty when page is out of range`() {
        val dates = listOf(today)

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 10,
            today = today,
            imageRevision = 1,
        )

        assertEquals(emptyList<ArchiveImageRequest>(), requests)
    }
}
