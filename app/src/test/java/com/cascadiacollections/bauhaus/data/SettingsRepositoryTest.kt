package com.cascadiacollections.bauhaus.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(WallpaperTarget.BOTH, repository.wallpaperTarget.first())
    }

    @Test
    fun `wallpaper target round-trips through the store`() = runTest {
        repository.setWallpaperTarget(WallpaperTarget.LOCK)

        assertEquals(WallpaperTarget.LOCK, repository.wallpaperTarget.first())
    }

    @Test
    fun `scheduling defaults to enabled`() = runTest {
        assertTrue(repository.schedulingEnabled.first())
    }

    @Test
    fun `scheduling can be turned off and back on`() = runTest {
        repository.setSchedulingEnabled(false)
        assertFalse(repository.schedulingEnabled.first())

        repository.setSchedulingEnabled(true)
        assertTrue(repository.schedulingEnabled.first())
    }

    @Test
    fun `last updated is null until a wallpaper has been set`() = runTest {
        assertNull(repository.lastUpdated.first())
    }

    @Test
    fun `last updated stores the date it was given`() = runTest {
        // The worker's "already set today" guard compares against this exact
        // string, so it must survive the round trip unmodified.
        repository.setLastUpdated("2025-03-04")

        assertEquals("2025-03-04", repository.lastUpdated.first())
    }

    @Test
    fun `last prefetched date is null until startup prefetch runs`() = runTest {
        assertNull(repository.getLastPrefetchedDate())
    }

    @Test
    fun `last prefetched date round-trips through the store`() = runTest {
        repository.setLastPrefetchedDate("2025-03-04")

        assertEquals("2025-03-04", repository.getLastPrefetchedDate())
    }

    @Test
    fun `first run is true until it is marked complete`() = runTest {
        assertTrue(repository.isFirstRun())

        repository.markFirstRunComplete()

        assertFalse(repository.isFirstRun())
    }

    @Test
    fun `marking first run complete twice leaves it complete`() = runTest {
        repository.markFirstRunComplete()
        repository.markFirstRunComplete()

        assertFalse(repository.isFirstRun())
    }

    @Test
    fun `favorites start empty`() = runTest {
        assertEquals(emptySet<String>(), repository.favorites.first())
    }

    @Test
    fun `toggling a favorite adds it and toggling again removes it`() = runTest {
        repository.toggleFavorite("2025-03-04")
        assertEquals(setOf("2025-03-04"), repository.favorites.first())

        repository.toggleFavorite("2025-03-04")
        assertEquals(emptySet<String>(), repository.favorites.first())
    }

    @Test
    fun `toggling one favorite off leaves the others alone`() = runTest {
        repository.toggleFavorite("2025-03-04")
        repository.toggleFavorite("2025-03-05")
        repository.toggleFavorite("2025-03-06")

        repository.toggleFavorite("2025-03-05")

        assertEquals(setOf("2025-03-04", "2025-03-06"), repository.favorites.first())
    }

    @Test
    fun `settings are independent of one another`() = runTest {
        repository.setWallpaperTarget(WallpaperTarget.HOME)
        repository.setSchedulingEnabled(false)
        repository.setLastUpdated("2025-03-04")
        repository.toggleFavorite("2025-03-04")
        repository.markFirstRunComplete()

        assertEquals(WallpaperTarget.HOME, repository.wallpaperTarget.first())
        assertFalse(repository.schedulingEnabled.first())
        assertEquals("2025-03-04", repository.lastUpdated.first())
        assertEquals(setOf("2025-03-04"), repository.favorites.first())
        assertFalse(repository.isFirstRun())
    }

    @Test
    fun `values written by one repository are visible to another over the same store`() = runTest {
        // Guards the process-restart path: preferences are on disk, not in the
        // repository instance.
        repository.setWallpaperTarget(WallpaperTarget.LOCK)
        repository.toggleFavorite("2025-03-04")

        val reopened = SettingsRepository(dataStore)

        assertEquals(WallpaperTarget.LOCK, reopened.wallpaperTarget.first())
        assertEquals(setOf("2025-03-04"), reopened.favorites.first())
    }
}
