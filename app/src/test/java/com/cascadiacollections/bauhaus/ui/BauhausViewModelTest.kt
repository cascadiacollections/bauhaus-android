package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.ServiceHealth
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.serviceToday
import java.time.Duration
import java.time.LocalDate
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

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
            SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── init ──────────────────────────────────────────────────────────────────

    @Test
    fun `init loads metadata from api`() {
        assertThat(viewModel.uiState.value.metadata).isEqualTo(FakeBauhausApi.DEFAULT_METADATA)
        assertThat(viewModel.uiState.value.isMetadataLoading).isFalse()
        assertThat(viewModel.uiState.value.metadataLoadFailed).isFalse()
    }

    @Test
    fun `init collects wallpaperTarget from settings`() {
        assertThat(viewModel.uiState.value.wallpaperTarget).isEqualTo(WallpaperTarget.BOTH)
    }

    @Test
    fun `init collects schedulingEnabled from settings`() {
        assertThat(viewModel.uiState.value.schedulingEnabled).isTrue()
    }

    @Test
    fun `init collects lastUpdated from settings`() {
        assertThat(viewModel.uiState.value.lastUpdated).isNull()
    }

    @Test
    fun `init gracefully handles metadata fetch failure`() {
        val failingApi = FakeBauhausApi().apply { shouldThrow = true }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            failingApi,
            FakeWallpaperScheduler(),
            SavedStateHandle()
        )
        assertThat(vm.uiState.value.metadata).isNull()
        assertThat(vm.uiState.value.isMetadataLoading).isFalse()
        assertThat(vm.uiState.value.metadataLoadFailed).isTrue()
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
            SavedStateHandle()
        )

        assertThat(vm.uiState.value.latestDate).isEqualTo(published)
        assertThat(vm.uiState.value.visibleDate).isEqualTo(published)
        assertThat(vm.uiState.value.availableDates).containsExactly(published)
        assertThat(vm.uiState.value.metadata?.title).isEqualTo("Yesterday")
    }

    @Test
    fun `init falls back to the utc clock when metadata omits a date`() {
        assertThat(viewModel.uiState.value.latestDate).isEqualTo(serviceToday())
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(serviceToday())
    }

    @Test
    fun `a missing latest day is reported as a stale service and re-anchors`() = runTest {
        val staleDate = serviceToday().minusDays(3)
        val api = FakeBauhausApi().apply {
            todayMetadataError = BauhausHttpException(404, "/api/today.json")
            healthToReturn = ServiceHealth(
                status = ServiceHealth.STATUS_STALE,
                date = staleDate.toString(),
                staleDays = 3
            )
        }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            api,
            FakeWallpaperScheduler(),
            SavedStateHandle()
        )
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.snackbarEvent.collect { events.add(it) }
        }

        // The init fetch already ran; re-trigger the same path through refresh so
        // the collector above sees the event.
        vm.refresh()

        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_service_stale)
        assertThat(events.map { it.message }).containsExactly(expected)
        assertThat(vm.uiState.value.latestDate).isEqualTo(staleDate)
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

        assertThat(fakeApi.healthCalls).isEqualTo(0)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_network)
        assertThat(events.map { it.message }).containsExactly(expected)
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

        assertThat(fakeApi.healthCalls).isEqualTo(1)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertThat(events.map { it.message }).containsExactly(expected)
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

        assertThat(fakeApi.healthCalls).isEqualTo(1)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertThat(events.map { it.message }).containsExactly(expected)
    }

    // ── saved state restoration ──────────────────────────────────────────────

    private fun viewModelWith(savedState: SavedStateHandle, api: FakeBauhausApi = FakeBauhausApi()) = BauhausViewModel(
        RuntimeEnvironment.getApplication(),
        FakeSettingsRepository(RuntimeEnvironment.getApplication()),
        api,
        FakeWallpaperScheduler(),
        savedState
    )

    @Test
    fun `browsing position is restored after process death`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to today.minusDays(2).toString(),
                    "oldest_browsed_date" to today.minusDays(4).toString()
                )
            )
        )

        assertThat(vm.uiState.value.visibleDate).isEqualTo(today.minusDays(2))
        assertThat(vm.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(2),
            today.minusDays(3),
            today.minusDays(4)
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
                    "oldest_browsed_date" to restoredDate.toString()
                )
            ),
            api
        )

        assertThat(vm.uiState.value.metadata?.title).isEqualTo("Restored")
        assertThat(vm.uiState.value.isMetadataLoading).isFalse()
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
                    "oldest_browsed_date" to restoredDate.toString()
                )
            ),
            api
        )

        // The restored page's own fetch already completed synchronously.
        assertThat(vm.uiState.value.metadata?.title).isEqualTo("Restored")
        assertThat(vm.uiState.value.isMetadataLoading).isFalse()
        assertThat(vm.uiState.value.metadataLoadFailed).isFalse()

        // Now let the anchor-date fetch fail. It must not wipe the restored page.
        gate.complete(Unit)

        assertThat(vm.uiState.value.visibleDate).isEqualTo(restoredDate)
        assertThat(vm.uiState.value.metadata?.title).isEqualTo("Restored")
        assertThat(vm.uiState.value.isMetadataLoading).isFalse()
        assertThat(vm.uiState.value.metadataLoadFailed).isFalse()
    }

    @Test
    fun `the favorites filter survives process death`() {
        val vm = viewModelWith(SavedStateHandle(mapOf("show_favorites_only" to true)))

        assertThat(vm.uiState.value.showFavoritesOnly).isTrue()
    }

    @Test
    fun `a restored span beyond the expansion limit is discarded`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(mapOf("oldest_browsed_date" to today.minusDays(5_000).toString()))
        )

        assertThat(vm.uiState.value.availableDates).containsExactly(today)
        assertThat(vm.uiState.value.visibleDate).isEqualTo(today)
    }

    @Test
    fun `a restored visible date outside the restored span falls back to today`() {
        val today = serviceToday()
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to today.minusDays(30).toString(),
                    "oldest_browsed_date" to today.minusDays(2).toString()
                )
            )
        )

        assertThat(vm.uiState.value.visibleDate).isEqualTo(today)
    }

    @Test
    fun `unparseable saved dates are ignored rather than crashing`() {
        val vm = viewModelWith(
            SavedStateHandle(
                mapOf(
                    "visible_date" to "not-a-date",
                    "oldest_browsed_date" to "also-not-a-date"
                )
            )
        )

        assertThat(vm.uiState.value.visibleDate).isEqualTo(serviceToday())
        assertThat(vm.uiState.value.availableDates).containsExactly(serviceToday())
    }

    // ── settings flow reactivity ─────────────────────────────────────────────

    @Test
    fun `uiState updates when wallpaperTarget flow emits`() {
        fakeSettings.emitWallpaperTarget(WallpaperTarget.HOME)
        assertThat(viewModel.uiState.value.wallpaperTarget).isEqualTo(WallpaperTarget.HOME)
    }

    @Test
    fun `uiState updates when schedulingEnabled flow emits`() {
        fakeSettings.emitSchedulingEnabled(false)
        assertThat(viewModel.uiState.value.schedulingEnabled).isFalse()
    }

    @Test
    fun `uiState updates when lastUpdated flow emits`() {
        fakeSettings.emitLastUpdated("2026-03-29")
        assertThat(viewModel.uiState.value.lastUpdated).isEqualTo("2026-03-29")
    }

    // ── setWallpaperTarget ───────────────────────────────────────────────────

    @Test
    fun `setWallpaperTarget delegates to settings`() {
        viewModel.setWallpaperTarget(WallpaperTarget.LOCK)
        assertThat(fakeSettings.lastSetTarget).isEqualTo(WallpaperTarget.LOCK)
    }

    @Test
    fun `setWallpaperTarget updates uiState via flow`() {
        viewModel.setWallpaperTarget(WallpaperTarget.HOME)
        assertThat(viewModel.uiState.value.wallpaperTarget).isEqualTo(WallpaperTarget.HOME)
    }

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `refresh updates metadata and increments imageRevision`() {
        val newMetadata = ArtworkMetadata(title = "New", artist = "New Artist")
        fakeApi.metadataToReturn = newMetadata

        viewModel.refresh()

        assertThat(viewModel.uiState.value.metadata).isEqualTo(newMetadata)
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun `refresh unexpected failure emits snackbar event`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.shouldThrow = true
        viewModel.refresh()

        assertThat(events).hasSize(1)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_refresh)
        assertThat(events[0].message).isEqualTo(expected)
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun `refresh network failure shows friendly message`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }

        fakeApi.throwIOException = true
        viewModel.refresh()

        assertThat(events).hasSize(1)
        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_network)
        assertThat(events[0].message).isEqualTo(expected)
        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
    }

    @Test
    fun `refresh is blocked by cooldown guard`() {
        viewModel.refresh()
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)

        val newMetadata = ArtworkMetadata(title = "Different", artist = "Different Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        // Still 1 — second call was blocked
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)
    }

    @Test
    fun `a failed refresh does not consume the cooldown`() {
        // Offline is exactly when a user pulls again; locking them out for 30 s
        // after a failure that never reached the service punishes the retry.
        fakeApi.throwIOException = true
        viewModel.refresh()
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(0)

        fakeApi.throwIOException = false
        val newMetadata = ArtworkMetadata(title = "Recovered", artist = "Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)
        assertThat(viewModel.uiState.value.metadata).isEqualTo(newMetadata)
    }

    @Test
    fun `refresh succeeds after cooldown expires`() {
        viewModel.refresh()
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        val newMetadata = ArtworkMetadata(title = "Different", artist = "Different Artist")
        fakeApi.metadataToReturn = newMetadata
        viewModel.refresh()

        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(2)
        assertThat(viewModel.uiState.value.metadata).isEqualTo(newMetadata)
    }

    @Test
    fun `a refresh requested while one is in flight costs one service call`() {
        val gate = CompletableDeferred<Unit>()
        fakeApi.todayMetadataGate = gate
        val callsBefore = fakeApi.todayMetadataCalls

        viewModel.refresh()
        assertThat(viewModel.uiState.value.isRefreshing).isTrue()

        // Second pull while the first is still waiting on the service. The
        // request channel has no free slot, so it is dropped rather than queued.
        viewModel.refresh()

        fakeApi.todayMetadataGate = null
        gate.complete(Unit)

        assertThat(viewModel.uiState.value.isRefreshing).isFalse()
        assertThat(viewModel.uiState.value.imageRevision).isEqualTo(1)
        assertThat(fakeApi.todayMetadataCalls - callsBefore).isEqualTo(1)
    }

    @Test
    fun `selecting oldest page appends older date when archive has data`() {
        val today = viewModel.uiState.value.visibleDate
        val expectedOlder = today.minusDays(1)
        fakeApi.dateMetadata[expectedOlder] = ArtworkMetadata(title = "Older", artist = "Archive")

        viewModel.onArchivePageSelected(0)

        assertThat(viewModel.uiState.value.availableDates).containsExactly(today, expectedOlder)
    }

    @Test
    fun `selecting older page updates visible date and metadata`() {
        val today = viewModel.uiState.value.visibleDate
        val older = today.minusDays(1)
        val olderMetadata = ArtworkMetadata(title = "Older", artist = "Archive")
        fakeApi.dateMetadata[older] = olderMetadata

        viewModel.onArchivePageSelected(0)
        viewModel.onArchivePageSelected(1)

        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(older)
        assertThat(viewModel.uiState.value.metadata).isEqualTo(olderMetadata)
    }

    @Test
    fun `archive is marked complete when older date returns 404`() {
        val today = viewModel.uiState.value.visibleDate
        fakeApi.missingDates += today.minusDays(1)

        viewModel.onArchivePageSelected(0)

        assertThat(viewModel.uiState.value.reachedArchiveStart).isTrue()
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today)
    }

    @Test
    fun `jumpToDate appends missing archive dates and selects requested day`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(3)
        val targetMetadata = ArtworkMetadata(title = "Jumped", artist = "Archive")
        fakeApi.dateMetadata[targetDate] = targetMetadata

        viewModel.jumpToDate(targetDate)

        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(targetDate)
        assertThat(viewModel.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(2),
            targetDate
        )
        assertThat(viewModel.uiState.value.metadata).isEqualTo(targetMetadata)
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
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(jumped)
        assertThat(viewModel.uiState.value.isMetadataLoading).isTrue()

        // Swipe to a nearer page, whose own load settles first.
        viewModel.onArchivePageSelected(2)
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(settled)
        assertThat(viewModel.uiState.value.metadata?.title).isEqualTo("Settled")

        // The late arrival is still cached, but must not overwrite what is shown
        // or clear a spinner that now belongs to a different page.
        gate.complete(Unit)

        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(settled)
        assertThat(viewModel.uiState.value.metadata?.title).isEqualTo("Settled")
        assertThat(viewModel.uiState.value.isMetadataLoading).isFalse()
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

        assertThat(viewModel.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(2),
            targetDate
        )
    }

    @Test
    fun `jumpToDate probes only the target date instead of every day in the span`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(400)
        fakeApi.dateMetadata[targetDate] = ArtworkMetadata(title = "Jumped", artist = "Archive")
        fakeApi.probedDates.clear()
        fakeApi.fetchedMetadataDates.clear()

        viewModel.jumpToDate(targetDate)

        assertThat(fakeApi.probedDates).containsExactly(targetDate)
        // Only the landed-on page needs its metadata; the 399 pages skipped over
        // must not each cost a request.
        assertThat(fakeApi.fetchedMetadataDates).containsExactly(targetDate)
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(targetDate)
        assertThat(viewModel.uiState.value.availableDates).hasSize(401)
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
        assertThat(events.map { it.message }).containsExactly(expected)
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(today)
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today)
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
        assertThat(events.map { it.message }).containsExactly(expected)
        assertThat(fakeApi.probedDates).isEmpty()
    }

    @Test
    fun `jumpToDate ignores future dates`() {
        val initialState = viewModel.uiState.value
        val futureDate = initialState.visibleDate.plusDays(1)

        viewModel.jumpToDate(futureDate)

        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(initialState.visibleDate)
        assertThat(viewModel.uiState.value.availableDates).isEqualTo(initialState.availableDates)
    }

    @Test
    fun `shareCurrentArtwork emits current artwork uri with metadata`() = runTest {
        val events = mutableListOf<ShareArtworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.shareArtworkEvent.collect { events.add(it) }
        }

        viewModel.shareCurrentArtwork()

        assertThat(events).hasSize(1)
        assertThat(events[0].uri.toString()).isEqualTo("${BauhausApi.BASE_URL}/api/today")
        assertThat(events[0].text).isEqualTo("Test — Test Artist\n${BauhausApi.BASE_URL}/api/today")
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

        assertThat(events).hasSize(1)
        assertThat(events[0].uri.toString()).isEqualTo("${BauhausApi.BASE_URL}/api/$older")
        assertThat(events[0].text).isEqualTo("Older — Archive\n${BauhausApi.BASE_URL}/api/$older")
    }

    @Test
    fun `shareCurrentArtwork falls back to uri when metadata unavailable`() = runTest {
        val failingApi = FakeBauhausApi().apply { shouldThrow = true }
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            failingApi,
            FakeWallpaperScheduler(),
            SavedStateHandle()
        )
        val events = mutableListOf<ShareArtworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.shareArtworkEvent.collect { events.add(it) }
        }

        vm.shareCurrentArtwork()
        assertThat(events).hasSize(1)
        assertThat(events[0].uri).isNotNull()
        assertThat(events[0].text).isEqualTo("${BauhausApi.BASE_URL}/api/today")
        assertThat(events[0].text).isEqualTo("${BauhausApi.BASE_URL}/api/today")
    }

    // ── toggleFavorite ───────────────────────────────────────────────────────

    @Test
    fun `toggleFavorite adds date to favorites`() {
        val date = viewModel.uiState.value.visibleDate
        assertThat(viewModel.uiState.value.isFavorite).isFalse()

        viewModel.toggleFavorite()

        assertThat(viewModel.uiState.value.isFavorite).isTrue()
        assertThat(fakeSettings.favoriteDatesSet).contains(date.toString())
    }

    @Test
    fun `toggleFavorite removes date when already favorited`() {
        viewModel.toggleFavorite()
        assertThat(viewModel.uiState.value.isFavorite).isTrue()

        viewModel.toggleFavorite()

        assertThat(viewModel.uiState.value.isFavorite).isFalse()
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

        assertThat(viewModel.uiState.value.showFavoritesOnly).isTrue()
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today)
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

        assertThat(viewModel.uiState.value.showFavoritesOnly).isFalse()
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today, older)
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

    private class FakeSettingsRepository(context: Context) : SettingsRepository(context) {
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

        fun emitWallpaperTarget(target: WallpaperTarget) {
            _wallpaperTarget.value = target
        }
        fun emitSchedulingEnabled(enabled: Boolean) {
            _schedulingEnabled.value = enabled
        }
        fun emitLastUpdated(date: String?) {
            _lastUpdated.value = date
        }

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
