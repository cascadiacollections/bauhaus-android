package com.cascadiacollections.bauhaus.ui

import androidx.compose.ui.unit.IntSize
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.ArtworkVariant
import java.time.LocalDate
import org.junit.Test

class SettingsScreenPrefetchTest {

    private val today = LocalDate.of(2026, 5, 10)

    @Test
    fun `neighborPrefetchRequests returns previous and next for middle page`() {
        val dates = listOf(today, today.minusDays(1), today.minusDays(2))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 1,
            latestDate = today,
            latestDateStatus = LatestDateStatus.CONFIRMED,
            imageRevision = 3
        )

        assertThat(requests).containsExactly(
            ArchiveImageRequest("/api/2026-05-10", "2026-05-10-3"),
            ArchiveImageRequest("/api/2026-05-08", "2026-05-08-3")
        )
    }

    @Test
    fun `neighborPrefetchRequests skips the newest page while its date is resolving`() {
        val dates = listOf(today, today.minusDays(1), today.minusDays(2))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 1,
            latestDate = today,
            latestDateStatus = LatestDateStatus.RESOLVING,
            imageRevision = 0
        )

        assertThat(requests).containsExactly(ArchiveImageRequest("/api/2026-05-08", "2026-05-08-0"))
    }

    @Test
    fun `the newest page loads its own immutable URL once the service confirms the date`() {
        // Loading /api/today under a date key cached whatever day /api/today was
        // on at the time — before the publish, yesterday's — under today's key.
        val request = archiveImageRequest(today, today, LatestDateStatus.CONFIRMED, imageRevision = 2)

        assertThat(request).isEqualTo(ArchiveImageRequest("/api/2026-05-10", "2026-05-10-2"))
    }

    @Test
    fun `an unconfirmed newest page uses today's route and stays out of Coil's disk cache`() {
        val request = archiveImageRequest(today, today, LatestDateStatus.UNCONFIRMED, imageRevision = 2)

        assertThat(request).isEqualTo(ArchiveImageRequest("/api/today", "today-2", diskCacheable = false))
    }

    @Test
    fun `older pages load their own URL whatever the newest date's status`() {
        val older = today.minusDays(1)

        LatestDateStatus.entries.forEach { status ->
            assertThat(archiveImageRequest(older, today, status, imageRevision = 0), name = "$status")
                .isEqualTo(ArchiveImageRequest("/api/2026-05-09", "2026-05-09-0"))
        }
    }

    @Test
    fun `neighborPrefetchRequests returns only one neighbor at edges`() {
        val dates = listOf(today, today.minusDays(1))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 0,
            latestDate = today,
            latestDateStatus = LatestDateStatus.CONFIRMED,
            imageRevision = 1
        )

        assertThat(requests).containsExactly(ArchiveImageRequest("/api/2026-05-09", "2026-05-09-1"))
    }

    @Test
    fun `neighborPrefetchRequests returns empty when page is out of range`() {
        val dates = listOf(today)

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 10,
            latestDate = today,
            latestDateStatus = LatestDateStatus.CONFIRMED,
            imageRevision = 1
        )

        assertThat(requests).isEmpty()
    }

    @Test
    fun `previewImageSizePx clamps oversize artwork cards to a safe request size`() {
        assertThat(previewImageSizePx(IntSize(4000, 3000))).isEqualTo(IntSize(1600, 1600))
        assertThat(previewImageSizePx(IntSize(1080, 810))).isEqualTo(IntSize(1080, 810))
    }

    @Test
    fun `previewAspectRatio uses the published stylized dimensions`() {
        val metadata = ArtworkMetadata(
            variants = listOf(ArtworkVariant(type = "stylized", width = 1280, height = 853))
        )

        assertThat(resolvePreviewAspectRatio(metadata)).isCloseTo(1280f / 853f, 0.0001f)
    }

    @Test
    fun `previewAspectRatio falls back when the service published no dimensions`() {
        assertThat(resolvePreviewAspectRatio(null)).isCloseTo(FALLBACK_ASPECT_RATIO, 0.0001f)
        assertThat(resolvePreviewAspectRatio(ArtworkMetadata())).isCloseTo(FALLBACK_ASPECT_RATIO, 0.0001f)
    }

    @Test
    fun `previewAspectRatio rejects implausible ratios rather than laying out a sliver`() {
        val panorama = ArtworkMetadata(
            variants = listOf(ArtworkVariant(type = "stylized", width = 10_000, height = 100))
        )

        assertThat(resolvePreviewAspectRatio(panorama)).isCloseTo(FALLBACK_ASPECT_RATIO, 0.0001f)
    }
}
