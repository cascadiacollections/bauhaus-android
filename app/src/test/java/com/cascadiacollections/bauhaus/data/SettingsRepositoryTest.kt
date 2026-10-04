package com.cascadiacollections.bauhaus.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises [SettingsRepository] against a real preferences store over a
 * temporary file, so every case starts from genuinely unset preferences and the
 * documented defaults are actually observed rather than assumed.
 */
class SettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Deliberately a path rather than newFile(): DataStore creates the file
        // itself, and it insists on the .preferences_pb extension.
        val file = File(temporaryFolder.root, "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        repository = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `wallpaper target defaults to BOTH`() = runTest {
        assertThat(repository.wallpaperTarget.first()).isEqualTo(WallpaperTarget.BOTH)
    }

    @Test
    fun `wallpaper target round-trips through the store`() = runTest {
        repository.setWallpaperTarget(WallpaperTarget.LOCK)

        assertThat(repository.wallpaperTarget.first()).isEqualTo(WallpaperTarget.LOCK)
    }

    @Test
    fun `scheduling defaults to enabled`() = runTest {
        assertThat(repository.schedulingEnabled.first()).isTrue()
    }

    @Test
    fun `scheduling can be turned off and back on`() = runTest {
        repository.setSchedulingEnabled(false)
        assertThat(repository.schedulingEnabled.first()).isFalse()

        repository.setSchedulingEnabled(true)
        assertThat(repository.schedulingEnabled.first()).isTrue()
    }

    @Test
    fun `last updated is null until a wallpaper has been set`() = runTest {
        assertThat(repository.lastUpdated.first()).isNull()
    }

    @Test
    fun `last updated stores the date it was given`() = runTest {
        // The worker's "already set today" guard compares against this exact
        // string, so it must survive the round trip unmodified.
        repository.setLastUpdated("2025-03-04")

        assertThat(repository.lastUpdated.first()).isEqualTo("2025-03-04")
    }

    @Test
    fun `last prefetched date is null until startup prefetch runs`() = runTest {
        assertThat(repository.getLastPrefetchedDate()).isNull()
    }

    @Test
    fun `last prefetched date round-trips through the store`() = runTest {
        repository.setLastPrefetchedDate("2025-03-04")

        assertThat(repository.getLastPrefetchedDate()).isEqualTo("2025-03-04")
    }

    @Test
    fun `first run is true until it is marked complete`() = runTest {
        assertThat(repository.isFirstRun()).isTrue()

        repository.markFirstRunComplete()

        assertThat(repository.isFirstRun()).isFalse()
    }

    @Test
    fun `marking first run complete twice leaves it complete`() = runTest {
        repository.markFirstRunComplete()
        repository.markFirstRunComplete()

        assertThat(repository.isFirstRun()).isFalse()
    }

    @Test
    fun `favorites start empty`() = runTest {
        assertThat(repository.favorites.first()).isEmpty()
    }

    @Test
    fun `toggling a favorite adds it and toggling again removes it`() = runTest {
        repository.toggleFavorite("2025-03-04")
        assertThat(repository.favorites.first()).containsOnly("2025-03-04")

        repository.toggleFavorite("2025-03-04")
        assertThat(repository.favorites.first()).isEmpty()
    }

    @Test
    fun `toggling one favorite off leaves the others alone`() = runTest {
        repository.toggleFavorite("2025-03-04")
        repository.toggleFavorite("2025-03-05")
        repository.toggleFavorite("2025-03-06")

        repository.toggleFavorite("2025-03-05")

        assertThat(repository.favorites.first()).containsOnly("2025-03-04", "2025-03-06")
    }

    @Test
    fun `settings are independent of one another`() = runTest {
        repository.setWallpaperTarget(WallpaperTarget.HOME)
        repository.setSchedulingEnabled(false)
        repository.setLastUpdated("2025-03-04")
        repository.toggleFavorite("2025-03-04")
        repository.markFirstRunComplete()

        assertThat(repository.wallpaperTarget.first()).isEqualTo(WallpaperTarget.HOME)
        assertThat(repository.schedulingEnabled.first()).isFalse()
        assertThat(repository.lastUpdated.first()).isEqualTo("2025-03-04")
        assertThat(repository.favorites.first()).containsOnly("2025-03-04")
        assertThat(repository.isFirstRun()).isFalse()
    }

    @Test
    fun `values written by one repository are visible to another over the same store`() = runTest {
        // Guards the process-restart path: preferences are on disk, not in the
        // repository instance.
        repository.setWallpaperTarget(WallpaperTarget.LOCK)
        repository.toggleFavorite("2025-03-04")

        val reopened = SettingsRepository(dataStore)

        assertThat(reopened.wallpaperTarget.first()).isEqualTo(WallpaperTarget.LOCK)
        assertThat(reopened.favorites.first()).containsOnly("2025-03-04")
    }
}
