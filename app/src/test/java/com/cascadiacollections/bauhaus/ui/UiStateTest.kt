package com.cascadiacollections.bauhaus.ui

import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {

    @Test
    fun `default state has expected values`() {
        val state = UiState()

        assertEquals(WallpaperTarget.BOTH, state.wallpaperTarget)
        assertTrue(state.schedulingEnabled)
        assertNull(state.lastUpdated)
        assertNull(state.metadata)
        assertFalse(state.isSettingWallpaper)
        assertFalse(state.isRefreshing)
        assertFalse(state.isSavingImage)
        assertNull(state.error)
        assertEquals(0, state.imageRevision)
    }

    @Test
    fun `imageRevision increments correctly via copy`() {
        val state = UiState()
        val updated = state.copy(imageRevision = state.imageRevision + 1)

        assertEquals(1, updated.imageRevision)
    }

    @Test
    fun `imageRevision preserves other fields when incremented`() {
        val metadata = ArtworkMetadata(title = "Test", artist = "Artist")
        val state = UiState(
            wallpaperTarget = WallpaperTarget.HOME,
            schedulingEnabled = false,
            lastUpdated = "2026-03-29",
            metadata = metadata,
            isSettingWallpaper = true,
            isRefreshing = true,
            isSavingImage = true,
            error = "some error",
            imageRevision = 5,
        )
        val updated = state.copy(imageRevision = state.imageRevision + 1)

        assertEquals(WallpaperTarget.HOME, updated.wallpaperTarget)
        assertFalse(updated.schedulingEnabled)
        assertEquals("2026-03-29", updated.lastUpdated)
        assertEquals(metadata, updated.metadata)
        assertTrue(updated.isSettingWallpaper)
        assertTrue(updated.isRefreshing)
        assertTrue(updated.isSavingImage)
        assertEquals("some error", updated.error)
        assertEquals(6, updated.imageRevision)
    }

    @Test
    fun `successful refresh increments imageRevision and clears refreshing`() {
        val state = UiState(isRefreshing = true, imageRevision = 3)
        val metadata = ArtworkMetadata(title = "New Art", artist = "New Artist")
        val afterRefresh = state.copy(
            metadata = metadata,
            isRefreshing = false,
            imageRevision = state.imageRevision + 1,
        )

        assertEquals(4, afterRefresh.imageRevision)
        assertFalse(afterRefresh.isRefreshing)
        assertEquals(metadata, afterRefresh.metadata)
    }

    @Test
    fun `failed refresh does not increment imageRevision`() {
        val state = UiState(isRefreshing = true, imageRevision = 3)
        val afterFailure = state.copy(
            isRefreshing = false,
            error = "Network error",
        )

        assertEquals(3, afterFailure.imageRevision)
        assertFalse(afterFailure.isRefreshing)
        assertEquals("Network error", afterFailure.error)
    }

    @Test
    fun `save in progress clears error`() {
        val state = UiState(error = "previous error")
        val saving = state.copy(isSavingImage = true, error = null)

        assertTrue(saving.isSavingImage)
        assertNull(saving.error)
    }

    @Test
    fun `save failure clears isSavingImage`() {
        val state = UiState(isSavingImage = true)
        val afterFailure = state.copy(isSavingImage = false)

        assertFalse(afterFailure.isSavingImage)
    }
}
