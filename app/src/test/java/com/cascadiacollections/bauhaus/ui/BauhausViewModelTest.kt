package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BauhausViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeApi: FakeBauhausApi
    private lateinit var fakeSettings: FakeSettingsRepository
    private lateinit var viewModel: BauhausViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeBauhausApi()
        fakeSettings = FakeSettingsRepository(RuntimeEnvironment.getApplication())
        // SystemClock starts at 0 in Robolectric; advance past the 30 s refresh
        // cooldown so the first call to refresh() in tests is not blocked.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        viewModel = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            fakeSettings,
            fakeApi,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── init ──────────────────────────────────────────────────────────────────

    @Test
    fun `init loads metadata from api`() {
        assertEquals(FakeBauhausApi.DEFAULT_METADATA, viewModel.uiState.value.metadata)
        assertFalse(viewModel.uiState.value.isMetadataLoading)
        assertFalse(viewModel.uiState.value.metadataLoadFailed)
    }

    @Test
    fun `init collects wallpaperTarget from settings`() {
        assertEquals(WallpaperTarget.BOTH, viewModel.uiState.value.wallpaperTarget)
    }

    @Test
    fun `init collects schedulingEnabled from settings`() {
        assertTrue(viewModel.uiState.value.schedulingEnabled)
    }

    @Test
    fun `init collects lastUpdated from settings`() {
        assertNull(viewModel.uiState.value.lastUpdated)
    }

    @Test
    fun `init gracefully handles metadata fetch failure`() {
        val failingApi = FakeBauhausApi().apply { shouldThrow = true }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            failingApi,
        )
        assertNull(vm.uiState.value.metadata)
        assertFalse(vm.uiState.value.isMetadataLoading)
        assertTrue(vm.uiState.value.metadataLoadFailed)
    }

    // ── settings flow reactivity ─────────────────────────────────────────────

    @Test
    fun `uiState updates when wallpaperTarget flow emits`() {
        fakeSettings.emitWallpaperTarget(WallpaperTarget.HOME)
        assertEquals(WallpaperTarget.HOME, viewModel.uiState.value.wallpaperTarget)
    }

    @Test
    fun `uiState updates when schedulingEnabled flow emits`() {
        fakeSettings.emitSchedulingEnabled(false)
        assertFalse(viewModel.uiState.value.schedulingEnabled)
    }

    @Test
    fun `uiState updates when lastUpdated flow emits`() {
        fakeSettings.emitLastUpdated("2026-03-29")
        assertEquals("2026-03-29", viewModel.uiState.value.lastUpdated)
    }

    // ── setWallpaperTarget ───────────────────────────────────────────────────

    @Test
    fun `setWallpaperTarget delegates to settings`() {
        viewModel.setWallpaperTarget(WallpaperTarget.LOCK)
        assertEquals(WallpaperTarget.LOCK, fakeSettings.lastSetTarget)
    }

    @Test
    fun `setWallpaperTarget updates uiState via flow`() {
        viewModel.setWallpaperTarget(WallpaperTarget.HOME)
        assertEquals(WallpaperTarget.HOME, viewModel.uiState.value.wallpaperTarget)
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh updates metadata and increments imageRevision`() {
        val newMetadata = ArtworkMetadata(title = "New", artist = "New Artist")
        fakeApi.metadataToReturn = newMetadata

        viewModel.refresh()

        assertEquals(newMetadata, viewModel.uiState.value.metadata)
        assertEquals(1, viewModel.uiState.value.imageRevision)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh unexpected failure emits snackbar event`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.shouldThrow = true
        viewModel.refresh()

        assertEquals(1, events.size)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertEquals(expected, events[0].message)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh network failure shows friendly message`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.throwIOException = true
        viewModel.refresh()

        assertEquals(1, events.size)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_network)
        assertEquals(expected, events[0].message)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh is blocked by cooldown guard`() {
        viewModel.refresh()
        assertEquals(1, viewModel.uiState.value.imageRevision)

        val newMetadata = ArtworkMetadata(title = "Different", artist = "Different Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        // Still 1 — second call was blocked
        assertEquals(1, viewModel.uiState.value.imageRevision)
    }

    @Test
    fun `refresh succeeds after cooldown expires`() {
        viewModel.refresh()
        assertEquals(1, viewModel.uiState.value.imageRevision)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        val newMetadata = ArtworkMetadata(title = "Different", artist = "Different Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        assertEquals(2, viewModel.uiState.value.imageRevision)
        assertEquals(newMetadata, viewModel.uiState.value.metadata)
    }

    @Test
    fun `selecting oldest page appends older date when archive has data`() {
        val today = viewModel.uiState.value.visibleDate
        val expectedOlder = today.minusDays(1)
        fakeApi.dateMetadata[expectedOlder] = ArtworkMetadata(title = "Older", artist = "Archive")

        viewModel.onArchivePageSelected(0)

        assertEquals(listOf(today, expectedOlder), viewModel.uiState.value.availableDates)
    }

    @Test
    fun `selecting older page updates visible date and metadata`() {
        val today = viewModel.uiState.value.visibleDate
        val older = today.minusDays(1)
        val olderMetadata = ArtworkMetadata(title = "Older", artist = "Archive")
        fakeApi.dateMetadata[older] = olderMetadata

        viewModel.onArchivePageSelected(0)
        viewModel.onArchivePageSelected(1)

        assertEquals(older, viewModel.uiState.value.visibleDate)
        assertEquals(olderMetadata, viewModel.uiState.value.metadata)
    }

    @Test
    fun `archive is marked complete when older date returns 404`() {
        val today = viewModel.uiState.value.visibleDate
        fakeApi.missingDates += today.minusDays(1)

        viewModel.onArchivePageSelected(0)

        assertTrue(viewModel.uiState.value.reachedArchiveStart)
        assertEquals(listOf(today), viewModel.uiState.value.availableDates)
    }

    @Test
    fun `jumpToDate appends missing archive dates and selects requested day`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(3)
        val targetMetadata = ArtworkMetadata(title = "Jumped", artist = "Archive")
        fakeApi.dateMetadata[targetDate] = targetMetadata

        viewModel.jumpToDate(targetDate)

        assertEquals(targetDate, viewModel.uiState.value.visibleDate)
        assertEquals(listOf(today, today.minusDays(1), today.minusDays(2), targetDate), viewModel.uiState.value.availableDates)
        assertEquals(targetMetadata, viewModel.uiState.value.metadata)
    }

    @Test
    fun `jumpToDate ignores future dates`() {
        val initialState = viewModel.uiState.value
        val futureDate = initialState.visibleDate.plusDays(1)

        viewModel.jumpToDate(futureDate)

        assertEquals(initialState.visibleDate, viewModel.uiState.value.visibleDate)
        assertEquals(initialState.availableDates, viewModel.uiState.value.availableDates)
    }

    @Test
    fun `shareCurrentArtwork emits current artwork uri with metadata`() = runTest {
        val events = mutableListOf<ShareArtworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.shareArtworkEvent.collect { events.add(it) }
        }

        viewModel.shareCurrentArtwork()

        assertEquals(1, events.size)
        assertEquals("${BauhausApi.BASE_URL}/api/today", events[0].uri.toString())
        assertEquals("Test — Test Artist\n${BauhausApi.BASE_URL}/api/today", events[0].text)
    }

    @Test
    fun `shareCurrentArtwork uses selected archive date uri`() = runTest {
        val events = mutableListOf<ShareArtworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.shareArtworkEvent.collect { events.add(it) }
        }

        val today = viewModel.uiState.value.visibleDate
        val older = today.minusDays(1)
        fakeApi.dateMetadata[older] = ArtworkMetadata(title = "Older", artist = "Archive")
        viewModel.onArchivePageSelected(0)
        viewModel.onArchivePageSelected(1)

        viewModel.shareCurrentArtwork()

        assertEquals(1, events.size)
        assertEquals("${BauhausApi.BASE_URL}/api/$older", events[0].uri.toString())
        assertEquals("Older — Archive\n${BauhausApi.BASE_URL}/api/$older", events[0].text)
    }

    @Test
    fun `shareCurrentArtwork falls back to uri when metadata unavailable`() = runTest {
        val failingApi = FakeBauhausApi().apply { shouldThrow = true }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            failingApi,
        )
        val events = mutableListOf<ShareArtworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.shareArtworkEvent.collect { events.add(it) }
        }

        vm.shareCurrentArtwork()
        assertEquals(1, events.size)
        assertNotNull(events[0].uri)
        assertEquals("${BauhausApi.BASE_URL}/api/today", events[0].text)
        assertEquals("${BauhausApi.BASE_URL}/api/today", events[0].text)
    }

    // ── toggleFavorite ───────────────────────────────────────────────────────

    @Test
    fun `toggleFavorite adds date to favorites`() {
        val date = viewModel.uiState.value.visibleDate
        assertFalse(viewModel.uiState.value.isFavorite)

        viewModel.toggleFavorite()

        assertTrue(viewModel.uiState.value.isFavorite)
        assertTrue(fakeSettings.favoriteDatesSet.contains(date.toString()))
    }

    @Test
    fun `toggleFavorite removes date when already favorited`() {
        viewModel.toggleFavorite()
        assertTrue(viewModel.uiState.value.isFavorite)

        viewModel.toggleFavorite()

        assertFalse(viewModel.uiState.value.isFavorite)
    }

    // ── toggleFavoritesFilter ────────────────────────────────────────────────

    @Test
    fun `toggleFavoritesFilter shows only favorites when favorites exist`() {
        val today = viewModel.uiState.value.visibleDate
        val older = today.minusDays(1)
        fakeApi.dateMetadata[older] = ArtworkMetadata(title = "Older", artist = "Archive")
        viewModel.onArchivePageSelected(0)

        viewModel.toggleFavorite()
        viewModel.toggleFavoritesFilter()

        assertTrue(viewModel.uiState.value.showFavoritesOnly)
        assertEquals(listOf(today), viewModel.uiState.value.availableDates)
    }

    @Test
    fun `toggleFavoritesFilter restores full list when exiting favorites mode`() {
        val today = viewModel.uiState.value.visibleDate
        val older = today.minusDays(1)
        fakeApi.dateMetadata[older] = ArtworkMetadata(title = "Older", artist = "Archive")
        viewModel.onArchivePageSelected(0)
        viewModel.toggleFavorite()
        viewModel.toggleFavoritesFilter()

        viewModel.toggleFavoritesFilter()

        assertFalse(viewModel.uiState.value.showFavoritesOnly)
        assertEquals(listOf(today, older), viewModel.uiState.value.availableDates)
    }

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeBauhausApi : BauhausApi(OkHttpClient()) {
        companion object {
            val DEFAULT_METADATA = ArtworkMetadata(title = "Test", artist = "Test Artist")
        }

        var metadataToReturn: ArtworkMetadata = DEFAULT_METADATA
        var shouldThrow = false
        var throwIOException = false
        val dateMetadata: MutableMap<LocalDate, ArtworkMetadata> = mutableMapOf()
        val missingDates: MutableSet<LocalDate> = mutableSetOf()

        override suspend fun fetchTodayMetadata(): ArtworkMetadata {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            return metadataToReturn
        }

        override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date.json")
            return dateMetadata[date] ?: ArtworkMetadata(title = "Date $date", artist = "Archive")
        }

        override suspend fun fetchTodayImage(maxWidth: Int, maxHeight: Int): Bitmap {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchImageForDate(date: LocalDate, maxWidth: Int, maxHeight: Int): Bitmap {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            return byteArrayOf(0) to "image/jpeg"
        }

        override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> {
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date")
            return byteArrayOf(0) to "image/jpeg"
        }
    }

    private class FakeSettingsRepository(
        context: Context,
    ) : SettingsRepository(context) {
        private val _wallpaperTarget = MutableStateFlow(WallpaperTarget.BOTH)
        override val wallpaperTarget: Flow<WallpaperTarget> = _wallpaperTarget

        private val _schedulingEnabled = MutableStateFlow(true)
        override val schedulingEnabled: Flow<Boolean> = _schedulingEnabled

        private val _lastUpdated = MutableStateFlow<String?>(null)
        override val lastUpdated: Flow<String?> = _lastUpdated

        private val _favorites = MutableStateFlow<Set<String>>(emptySet())
        override val favorites: Flow<Set<String>> = _favorites

        val favoriteDatesSet: Set<String> get() = _favorites.value

        var lastSetTarget: WallpaperTarget? = null

        fun emitWallpaperTarget(target: WallpaperTarget) { _wallpaperTarget.value = target }
        fun emitSchedulingEnabled(enabled: Boolean) { _schedulingEnabled.value = enabled }
        fun emitLastUpdated(date: String?) { _lastUpdated.value = date }

        override suspend fun setWallpaperTarget(target: WallpaperTarget) {
            lastSetTarget = target
            _wallpaperTarget.value = target
        }

        override suspend fun setSchedulingEnabled(enabled: Boolean) {
            _schedulingEnabled.value = enabled
        }

        override suspend fun setLastUpdated(date: String) {
            _lastUpdated.value = date
        }

        override suspend fun toggleFavorite(date: String) {
            _favorites.value = if (date in _favorites.value) {
                _favorites.value - date
            } else {
                _favorites.value + date
            }
        }
    }
}
