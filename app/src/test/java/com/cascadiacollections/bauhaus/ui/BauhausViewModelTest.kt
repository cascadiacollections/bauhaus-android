package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.ServiceHealth
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.serviceToday
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var fakeScheduler: FakeWallpaperScheduler
    private lateinit var viewModel: BauhausViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeApi = FakeBauhausApi()
        fakeSettings = FakeSettingsRepository(RuntimeEnvironment.getApplication())
        fakeScheduler = FakeWallpaperScheduler()
        // SystemClock starts at 0 in Robolectric; advance past the 30 s refresh
        // cooldown so the first call to refresh() in tests is not blocked.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        viewModel = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            fakeSettings,
            fakeApi,
            fakeScheduler,
            SavedStateHandle(),
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
            FakeWallpaperScheduler(),
            SavedStateHandle(),
        )
        assertNull(vm.uiState.value.metadata)
        assertFalse(vm.uiState.value.isMetadataLoading)
        assertTrue(vm.uiState.value.metadataLoadFailed)
    }

    @Test
    fun `init anchors browsing to the date the service says it published`() {
        // The device clock and the service's UTC publish date disagree for part of
        // every day; the service's answer wins.
        val published = serviceToday().minusDays(1)
        val api = FakeBauhausApi().apply {
            metadataToReturn = ArtworkMetadata(title = "Yesterday", date = published.toString())
        }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            api,
            FakeWallpaperScheduler(),
            SavedStateHandle(),
        )

        assertEquals(published, vm.uiState.value.latestDate)
        assertEquals(published, vm.uiState.value.visibleDate)
        assertEquals(listOf(published), vm.uiState.value.availableDates)
        assertEquals("Yesterday", vm.uiState.value.metadata?.title)
    }

    @Test
    fun `init falls back to the utc clock when metadata omits a date`() {
        assertEquals(serviceToday(), viewModel.uiState.value.latestDate)
        assertEquals(serviceToday(), viewModel.uiState.value.visibleDate)
    }

    @Test
    fun `a missing latest day is reported as a stale service and re-anchors`() = runTest {
        val staleDate = serviceToday().minusDays(3)
        val api = FakeBauhausApi().apply {
            todayMetadataError = BauhausHttpException(404, "/api/today.json")
            healthToReturn = ServiceHealth(
                status = ServiceHealth.STATUS_STALE,
                date = staleDate.toString(),
                staleDays = 3,
            )
        }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            api,
            FakeWallpaperScheduler(),
            SavedStateHandle(),
        )
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.snackbarEvent.collect { events.add(it) }
        }

        // The init fetch already ran; re-trigger the same path through refresh so
        // the collector above sees the event.
        vm.refresh()

        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_service_stale)
        assertEquals(listOf(expected), events.map { it.message })
        assertEquals(staleDate, vm.uiState.value.latestDate)
    }

    @Test
    fun `an offline metadata failure does not probe health`() = runTest {
        // The probe costs a request that is certain to fail, and being offline
        // already explains the error.
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.throwIOException = true
        viewModel.refresh()

        assertEquals(0, fakeApi.healthCalls)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_network)
        assertEquals(listOf(expected), events.map { it.message })
    }

    @Test
    fun `a 404 whose health probe fails falls back to the generic report`() = runTest {
        // An unanswered health check is no evidence that the service is behind.
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.todayMetadataError = BauhausHttpException(404, "/api/today.json")
        fakeApi.healthError = RuntimeException("health unreachable")
        viewModel.refresh()

        assertEquals(1, fakeApi.healthCalls)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertEquals(listOf(expected), events.map { it.message })
    }

    @Test
    fun `a 404 on a healthy service is reported as a fault not a stale service`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.todayMetadataError = BauhausHttpException(404, "/api/today.json")
        fakeApi.healthToReturn = ServiceHealth(status = ServiceHealth.STATUS_OK)
        viewModel.refresh()

        assertEquals(1, fakeApi.healthCalls)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertEquals(listOf(expected), events.map { it.message })
    }

    // ── saved state restoration ──────────────────────────────────────────────

    private fun viewModelWith(savedState: SavedStateHandle, api: FakeBauhausApi = FakeBauhausApi()) =
        BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            api,
            FakeWallpaperScheduler(),
            savedState,
        )

    @Test
    fun `browsing position is restored after process death`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to today.minusDays(2).toString(),
                    "oldest_browsed_date" to today.minusDays(4).toString(),
                ),
            ),
        )

        assertEquals(today.minusDays(2), vm.uiState.value.visibleDate)
        assertEquals(
            listOf(
                today,
                today.minusDays(1),
                today.minusDays(2),
                today.minusDays(3),
                today.minusDays(4),
            ),
            vm.uiState.value.availableDates,
        )
    }

    @Test
    fun `a restored page loads its own metadata rather than todays`() {
        // The startup fetch asks for the anchor date. Without an explicit load for
        // the restored page the card would settle into "no metadata, not loading,
        // not failed" and render nothing.
        val today = serviceToday()
        val restoredDate = today.minusDays(2)
        val api = FakeBauhausApi().apply {
            dateMetadata[restoredDate] = ArtworkMetadata(title = "Restored", artist = "Archive")
        }
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to restoredDate.toString(),
                    "oldest_browsed_date" to restoredDate.toString(),
                ),
            ),
            api,
        )

        assertEquals("Restored", vm.uiState.value.metadata?.title)
        assertFalse(vm.uiState.value.isMetadataLoading)
    }

    @Test
    fun `a slow anchor-date fetch does not clobber an already-settled restored page`() {
        // The restored page's own load can settle before the startup fetch for
        // anchorDate returns. That startup fetch must not touch isMetadataLoading,
        // metadataLoadFailed, or metadata for a page other than the one it is for.
        val today = serviceToday()
        val restoredDate = today.minusDays(2)
        val gate = CompletableDeferred<Unit>()
        val api = FakeBauhausApi().apply {
            dateMetadata[restoredDate] = ArtworkMetadata(title = "Restored", artist = "Archive")
            todayMetadataGate = gate
            todayMetadataError = BauhausHttpException(404, "/api/today.json")
        }
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to restoredDate.toString(),
                    "oldest_browsed_date" to restoredDate.toString(),
                ),
            ),
            api,
        )

        // The restored page's own fetch already completed synchronously.
        assertEquals("Restored", vm.uiState.value.metadata?.title)
        assertFalse(vm.uiState.value.isMetadataLoading)
        assertFalse(vm.uiState.value.metadataLoadFailed)

        // Now let the anchor-date fetch fail. It must not wipe the restored page.
        gate.complete(Unit)

        assertEquals(restoredDate, vm.uiState.value.visibleDate)
        assertEquals("Restored", vm.uiState.value.metadata?.title)
        assertFalse(vm.uiState.value.isMetadataLoading)
        assertFalse(vm.uiState.value.metadataLoadFailed)
    }

    @Test
    fun `the favorites filter survives process death`() {
        val vm = viewModelWith(SavedStateHandle(mapOf("show_favorites_only" to true)))

        assertTrue(vm.uiState.value.showFavoritesOnly)
    }

    @Test
    fun `a restored span beyond the expansion limit is discarded`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(mapOf("oldest_browsed_date" to today.minusDays(5_000).toString())),
        )

        assertEquals(listOf(today), vm.uiState.value.availableDates)
        assertEquals(today, vm.uiState.value.visibleDate)
    }

    @Test
    fun `a restored visible date outside the restored span falls back to today`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to today.minusDays(30).toString(),
                    "oldest_browsed_date" to today.minusDays(2).toString(),
                ),
            ),
        )

        assertEquals(today, vm.uiState.value.visibleDate)
    }

    @Test
    fun `unparseable saved dates are ignored rather than crashing`() {
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to "not-a-date",
                    "oldest_browsed_date" to "also-not-a-date",
                ),
            ),
        )

        assertEquals(serviceToday(), vm.uiState.value.visibleDate)
        assertEquals(listOf(serviceToday()), vm.uiState.value.availableDates)
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
    fun `a failed refresh does not consume the cooldown`() {
        // Offline is exactly when a user pulls again; locking them out for 30 s
        // after a failure that never reached the service punishes the retry.
        fakeApi.throwIOException = true
        viewModel.refresh()
        assertEquals(0, viewModel.uiState.value.imageRevision)

        fakeApi.throwIOException = false
        val newMetadata = ArtworkMetadata(title = "Recovered", artist = "Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        assertEquals(1, viewModel.uiState.value.imageRevision)
        assertEquals(newMetadata, viewModel.uiState.value.metadata)
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
    fun `a refresh requested while one is in flight costs one service call`() {
        val gate = CompletableDeferred<Unit>()
        fakeApi.todayMetadataGate = gate
        val callsBefore = fakeApi.todayMetadataCalls

        viewModel.refresh()
        assertTrue(viewModel.uiState.value.isRefreshing)

        // Second pull while the first is still waiting on the service. The
        // request channel has no free slot, so it is dropped rather than queued.
        viewModel.refresh()

        fakeApi.todayMetadataGate = null
        gate.complete(Unit)

        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals(1, viewModel.uiState.value.imageRevision)
        assertEquals(1, fakeApi.todayMetadataCalls - callsBefore)
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
    fun `metadata for a page the user swiped away from does not reach the screen`() {
        val today = viewModel.uiState.value.visibleDate
        val jumped = today.minusDays(3)
        val settled = today.minusDays(2)
        fakeApi.dateMetadata[jumped] = ArtworkMetadata(title = "Jumped", artist = "Archive")
        fakeApi.dateMetadata[settled] = ArtworkMetadata(title = "Settled", artist = "Archive")

        // Park the jumped page's metadata mid-flight.
        val gate = CompletableDeferred<Unit>()
        fakeApi.dateMetadataGates[jumped] = gate
        viewModel.jumpToDate(jumped)
        assertEquals(jumped, viewModel.uiState.value.visibleDate)
        assertTrue(viewModel.uiState.value.isMetadataLoading)

        // Swipe to a nearer page, whose own load settles first.
        viewModel.onArchivePageSelected(2)
        assertEquals(settled, viewModel.uiState.value.visibleDate)
        assertEquals("Settled", viewModel.uiState.value.metadata?.title)

        // The late arrival is still cached, but must not overwrite what is shown
        // or clear a spinner that now belongs to a different page.
        gate.complete(Unit)

        assertEquals(settled, viewModel.uiState.value.visibleDate)
        assertEquals("Settled", viewModel.uiState.value.metadata?.title)
        assertFalse(viewModel.uiState.value.isMetadataLoading)
    }

    @Test
    fun `jumpToDate keeps browsed archive dates when favorites filter toggles`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(3)
        fakeApi.dateMetadata[targetDate] = ArtworkMetadata(title = "Jumped", artist = "Archive")

        viewModel.jumpToDate(targetDate)
        viewModel.toggleFavorite()
        viewModel.toggleFavoritesFilter()
        viewModel.toggleFavoritesFilter()

        assertEquals(listOf(today, today.minusDays(1), today.minusDays(2), targetDate), viewModel.uiState.value.availableDates)
    }

    @Test
    fun `jumpToDate probes only the target date instead of every day in the span`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(400)
        fakeApi.dateMetadata[targetDate] = ArtworkMetadata(title = "Jumped", artist = "Archive")
        fakeApi.probedDates.clear()
        fakeApi.fetchedMetadataDates.clear()

        viewModel.jumpToDate(targetDate)

        assertEquals(listOf(targetDate), fakeApi.probedDates)
        // Only the landed-on page needs its metadata; the 399 pages skipped over
        // must not each cost a request.
        assertEquals(listOf(targetDate), fakeApi.fetchedMetadataDates)
        assertEquals(targetDate, viewModel.uiState.value.visibleDate)
        assertEquals(401, viewModel.uiState.value.availableDates.size)
    }

    @Test
    fun `jumpToDate reports when the service has no artwork for that date`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(5)
        fakeApi.missingDates += targetDate

        viewModel.jumpToDate(targetDate)

        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_no_artwork_for_date)
        assertEquals(listOf(expected), events.map { it.message })
        assertEquals(today, viewModel.uiState.value.visibleDate)
        assertEquals(listOf(today), viewModel.uiState.value.availableDates)
    }

    @Test
    fun `jumpToDate refuses spans beyond the expansion limit without a request`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        val today = viewModel.uiState.value.visibleDate
        fakeApi.probedDates.clear()

        viewModel.jumpToDate(today.minusDays(1000))

        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_archive_jump_too_far)
        assertEquals(listOf(expected), events.map { it.message })
        assertTrue(fakeApi.probedDates.isEmpty())
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
            FakeWallpaperScheduler(),
            SavedStateHandle(),
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

    private class FakeBauhausApi : BauhausApiClient {
        companion object {
            val DEFAULT_METADATA = ArtworkMetadata(title = "Test", artist = "Test Artist")
        }

        var metadataToReturn: ArtworkMetadata = DEFAULT_METADATA
        var shouldThrow = false
        var throwIOException = false
        var healthToReturn: ServiceHealth = ServiceHealth(status = ServiceHealth.STATUS_OK)
        val dateMetadata: MutableMap<LocalDate, ArtworkMetadata> = mutableMapOf()
        val missingDates: MutableSet<LocalDate> = mutableSetOf()

        /** Dates probed via [hasArtworkForDate], in call order. */
        val probedDates: MutableList<LocalDate> = mutableListOf()

        /** Dates whose metadata was actually fetched, in call order. */
        val fetchedMetadataDates: MutableList<LocalDate> = mutableListOf()

        override suspend fun hasArtworkForDate(date: LocalDate): Boolean {
            probedDates += date
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            return date !in missingDates
        }

        /** Number of times [fetchHealth] has been called. */
        var healthCalls = 0

        /** Thrown from [fetchHealth] when set, simulating an unreachable probe. */
        var healthError: Throwable? = null

        override suspend fun fetchHealth(): ServiceHealth {
            healthCalls++
            healthError?.let { throw it }
            return healthToReturn
        }

        /** Thrown from [fetchTodayMetadata] when set, in preference to the flags above. */
        var todayMetadataError: Throwable? = null

        /** Number of times [fetchTodayMetadata] has been entered. */
        var todayMetadataCalls = 0

        /** When set, [fetchTodayMetadata] parks until it completes, simulating a slow service. */
        var todayMetadataGate: CompletableDeferred<Unit>? = null

        override suspend fun fetchTodayMetadata(): ArtworkMetadata {
            todayMetadataCalls++
            todayMetadataGate?.await()
            todayMetadataError?.let { throw it }
            if (throwIOException) throw java.io.IOException("Unable to resolve host")
            if (shouldThrow) throw RuntimeException("Unexpected error")
            return metadataToReturn
        }

        /** Dates whose [fetchMetadataForDate] parks until the deferred completes. */
        val dateMetadataGates: MutableMap<LocalDate, CompletableDeferred<Unit>> = mutableMapOf()

        override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata {
            fetchedMetadataDates += date
            dateMetadataGates[date]?.await()
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

    private class FakeWallpaperScheduler : com.cascadiacollections.bauhaus.WallpaperScheduler {
        var scheduled = false
        var cancelled = false
        var immediateRequests = 0

        override fun scheduleDaily() {
            scheduled = true
        }

        override fun cancelDaily() {
            cancelled = true
        }

        override fun requestImmediateUpdate() {
            immediateRequests++
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
