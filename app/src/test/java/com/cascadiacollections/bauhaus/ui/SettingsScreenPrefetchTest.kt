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
            imageRevision = 3
        )

        assertThat(requests).containsExactly(
            ArchiveImageRequest("/api/today", "2026-05-10-3"),
            ArchiveImageRequest("/api/2026-05-08", "2026-05-08-3")
        )
    }

    @Test
    fun `neighborPrefetchRequests returns only one neighbor at edges`() {
        val dates = listOf(today, today.minusDays(1))

        val requests = neighborPrefetchRequests(
            dates = dates,
            settledPage = 0,
            latestDate = today,
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
