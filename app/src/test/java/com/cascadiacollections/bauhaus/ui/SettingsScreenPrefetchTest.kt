package com.cascadiacollections.bauhaus.ui

import androidx.compose.ui.unit.IntSize
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.ArtworkVariant
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
            latestDate = today,
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
            latestDate = today,
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
            latestDate = today,
            imageRevision = 1,
        )

        assertEquals(emptyList<ArchiveImageRequest>(), requests)
    }

    @Test
    fun `previewImageSizePx clamps oversize artwork cards to a safe request size`() {
        assertEquals(IntSize(1600, 1600), previewImageSizePx(IntSize(4000, 3000)))
        assertEquals(IntSize(1080, 810), previewImageSizePx(IntSize(1080, 810)))
    }

    @Test
    fun `previewAspectRatio uses the published stylized dimensions`() {
        val metadata = ArtworkMetadata(
            variants = listOf(ArtworkVariant(type = "stylized", width = 1280, height = 853)),
        )

        assertEquals(1280f / 853f, resolvePreviewAspectRatio(metadata), 0.0001f)
    }

    @Test
    fun `previewAspectRatio falls back when the service published no dimensions`() {
        assertEquals(FALLBACK_ASPECT_RATIO, resolvePreviewAspectRatio(null), 0.0001f)
        assertEquals(FALLBACK_ASPECT_RATIO, resolvePreviewAspectRatio(ArtworkMetadata()), 0.0001f)
    }

    @Test
    fun `previewAspectRatio rejects implausible ratios rather than laying out a sliver`() {
        val panorama = ArtworkMetadata(
            variants = listOf(ArtworkVariant(type = "stylized", width = 10_000, height = 100)),
        )

        assertEquals(FALLBACK_ASPECT_RATIO, resolvePreviewAspectRatio(panorama), 0.0001f)
    }
}
