package com.cascadiacollections.bauhaus.ui

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import org.junit.Test

class UiStateTest {

    @Test
    fun `default state has expected values`() {
        val state = UiState()

        assertThat(state.wallpaperTarget).isEqualTo(WallpaperTarget.BOTH)
        assertThat(state.schedulingEnabled).isTrue()
        assertThat(state.lastUpdated).isNull()
        assertThat(state.metadata).isNull()
        assertThat(state.isSettingWallpaper).isFalse()
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.isSavingImage).isFalse()
        assertThat(state.imageRevision).isEqualTo(0)
        assertThat(state.isFavorite).isFalse()
        assertThat(state.showFavoritesOnly).isFalse()
        assertThat(state.favoriteDates).isEmpty()
    }

    @Test
    fun `imageRevision increments correctly via copy`() {
        val state = UiState()
        val updated = state.copy(imageRevision = state.imageRevision + 1)

        assertThat(updated.imageRevision).isEqualTo(1)
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
            imageRevision = 5
        )
        val updated = state.copy(imageRevision = state.imageRevision + 1)

        assertThat(updated.wallpaperTarget).isEqualTo(WallpaperTarget.HOME)
        assertThat(updated.schedulingEnabled).isFalse()
        assertThat(updated.lastUpdated).isEqualTo("2026-03-29")
        assertThat(updated.metadata).isEqualTo(metadata)
        assertThat(updated.isSettingWallpaper).isTrue()
        assertThat(updated.isRefreshing).isTrue()
        assertThat(updated.isSavingImage).isTrue()
        assertThat(updated.imageRevision).isEqualTo(6)
    }

    @Test
    fun `successful refresh increments imageRevision and clears refreshing`() {
        val state = UiState(isRefreshing = true, imageRevision = 3)
        val metadata = ArtworkMetadata(title = "New Art", artist = "New Artist")
        val afterRefresh = state.copy(
            metadata = metadata,
            isRefreshing = false,
            imageRevision = state.imageRevision + 1
        )

        assertThat(afterRefresh.imageRevision).isEqualTo(4)
        assertThat(afterRefresh.isRefreshing).isFalse()
        assertThat(afterRefresh.metadata).isEqualTo(metadata)
    }

    @Test
    fun `failed refresh does not increment imageRevision`() {
        val state = UiState(isRefreshing = true, imageRevision = 3)
        val afterFailure = state.copy(isRefreshing = false)

        assertThat(afterFailure.imageRevision).isEqualTo(3)
        assertThat(afterFailure.isRefreshing).isFalse()
    }

    @Test
    fun `save in progress is reflected in state`() {
        val state = UiState()
        val saving = state.copy(isSavingImage = true)

        assertThat(saving.isSavingImage).isTrue()
    }

    @Test
    fun `save completion clears isSavingImage`() {
        val state = UiState(isSavingImage = true)
        val afterSave = state.copy(isSavingImage = false)

        assertThat(afterSave.isSavingImage).isFalse()
    }
}
