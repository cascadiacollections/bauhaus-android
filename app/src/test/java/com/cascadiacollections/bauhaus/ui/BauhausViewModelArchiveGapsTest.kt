package com.cascadiacollections.bauhaus.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.serviceToday
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * The archive pager over a published archive that has gaps: days whose
 * pipeline run failed have no artwork, and must be neither pages nor the end.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BauhausViewModelArchiveGapsTest {

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

    private fun viewModelWith(savedState: SavedStateHandle, api: FakeBauhausApi = FakeBauhausApi()) = BauhausViewModel(
        RuntimeEnvironment.getApplication(),
        FakeSettingsRepository(RuntimeEnvironment.getApplication()),
        api,
        FakeWallpaperScheduler(),
        savedState
    )

    @Test
    fun `days the service never published stay out of the pager after process death`() {
        val today = serviceToday()
        val savedState = SavedStateHandle()
        val api = FakeBauhausApi().apply { missingDates += today.minusDays(2) }
        val before = viewModelWith(savedState, api)
        before.jumpToDate(today.minusDays(3))

        val restored = viewModelWith(savedState)

        assertThat(restored.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(3)
        )
    }

    @Test
    fun `a day the service never published is skipped rather than ending the archive`() {
        // The live archive has 2026-10-01 and 2026-10-03 but no 2026-10-02.
        val today = viewModel.uiState.value.visibleDate
        fakeApi.missingDates += today.minusDays(1)

        viewModel.onArchivePageSelected(0)

        assertThat(viewModel.uiState.value.reachedArchiveStart).isFalse()
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today, today.minusDays(2))
    }

    @Test
    fun `a gap longer than any probing bound is skipped too`() {
        // July 2026 lost eleven consecutive days.
        val today = viewModel.uiState.value.visibleDate
        fakeApi.missingDates += (1L..11L).map { today.minusDays(it) }

        viewModel.onArchivePageSelected(0)

        assertThat(viewModel.uiState.value.reachedArchiveStart).isFalse()
        assertThat(viewModel.uiState.value.availableDates).containsExactly(today, today.minusDays(12))
    }

    @Test
    fun `paging back reads the archive index once, not once per page`() {
        val today = viewModel.uiState.value.visibleDate
        fakeApi.archiveRequests.clear()

        viewModel.onArchivePageSelected(0)
        viewModel.onArchivePageSelected(1)
        viewModel.onArchivePageSelected(2)

        assertThat(viewModel.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(2),
            today.minusDays(3)
        )
        assertThat(fakeApi.archiveRequests).hasSize(1)
    }

    @Test
    fun `a failed index read does not end the archive`() {
        fakeApi.throwIOException = true

        viewModel.onArchivePageSelected(0)

        assertThat(viewModel.uiState.value.reachedArchiveStart).isFalse()
    }

    @Test
    fun `jumpToDate appends only the days the service published`() {
        val today = viewModel.uiState.value.visibleDate
        val targetDate = today.minusDays(4)
        fakeApi.missingDates += today.minusDays(2)

        viewModel.jumpToDate(targetDate)

        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(targetDate)
        assertThat(viewModel.uiState.value.availableDates).containsExactly(
            today,
            today.minusDays(1),
            today.minusDays(3),
            targetDate
        )
    }

    @Test
    fun `jumpToDate onto a skipped day inside the loaded span reports no artwork`() = runTest {
        val events = mutableListOf<SnackbarEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarEvent.collect { events.add(it) }
        }
        val today = viewModel.uiState.value.visibleDate
        val gap = today.minusDays(2)
        fakeApi.missingDates += gap
        viewModel.jumpToDate(today.minusDays(4))

        viewModel.jumpToDate(gap)

        val expected = RuntimeEnvironment.getApplication().getString(R.string.error_no_artwork_for_date)
        assertThat(events.map { it.message }).containsExactly(expected)
        assertThat(viewModel.uiState.value.visibleDate).isEqualTo(today.minusDays(4))
        assertThat(viewModel.uiState.value.availableDates).hasSize(4)
    }
}
