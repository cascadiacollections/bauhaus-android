package com.cascadiacollections.bauhaus.ui

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.serviceToday
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 * Which day the newest artwork belongs to, and that "Set Now" stamps that
 * day rather than the clock's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BauhausViewModelPublishedDayTest {

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
    fun `init does not mistake the artwork's own date for the publish date`() {
        // Shaped after the live /api/today.json: `date` is the Met object date.
        val published = serviceToday().minusDays(1)
        val api = FakeBauhausApi().apply {
            metadataToReturn = ArtworkMetadata(
                title = "Pre-publish",
                date = "ca. 1750",
                generatedAt = "${published}T05:27:53.363896+00:00"
            )
        }

        val vm = viewModelWith(SavedStateHandle(), api)

        assertThat(vm.uiState.value.latestDate).isEqualTo(published)
        assertThat(vm.uiState.value.latestDateStatus).isEqualTo(LatestDateStatus.CONFIRMED)
        assertThat(vm.uiState.value.metadata?.title).isEqualTo("Pre-publish")
    }

    @Test
    fun `the newest date stays unconfirmed when the service cannot be asked`() {
        val api = FakeBauhausApi().apply { throwIOException = true }

        val vm = viewModelWith(SavedStateHandle(), api)

        assertThat(vm.uiState.value.latestDateStatus).isEqualTo(LatestDateStatus.UNCONFIRMED)
    }

    @Test
    fun `Set Now stamps the day the service published, not the clock's day`() {
        // Before the day's publish the newest artwork is yesterday's. Stamping the
        // clock's day would make the worker skip the run after publishing.
        val published = serviceToday().minusDays(1)
        val api = FakeBauhausApi().apply {
            metadataToReturn = ArtworkMetadata(publishedDateRaw = published.toString())
        }
        val settings = FakeSettingsRepository(RuntimeEnvironment.getApplication())
        val setter = RecordingWallpaperSetter()
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            settings,
            api,
            FakeWallpaperScheduler(),
            SavedStateHandle(),
            setter
        )

        vm.setWallpaperNow()

        assertThat(setter.calls).isEqualTo(1)
        assertThat(api.imageDates).containsExactly(published)
        assertThat(vm.uiState.value.lastUpdated).isEqualTo(published.toString())
    }

    @Test
    fun `Set Now on an unconfirmed newest page asks the service which day it is`() {
        val published = serviceToday().minusDays(1)
        val api = FakeBauhausApi().apply { throwIOException = true }
        val setter = RecordingWallpaperSetter()
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            FakeSettingsRepository(RuntimeEnvironment.getApplication()),
            api,
            FakeWallpaperScheduler(),
            SavedStateHandle(),
            setter
        )
        api.throwIOException = false
        api.metadataToReturn = ArtworkMetadata(publishedDateRaw = published.toString())

        vm.setWallpaperNow()

        assertThat(api.imageDates).containsExactly(published)
        assertThat(vm.uiState.value.lastUpdated).isEqualTo(published.toString())
    }

    @Test
    fun `Set Now stamps nothing when the service names no day`() {
        val setter = RecordingWallpaperSetter()
        val vm = BauhausViewModel(
            RuntimeEnvironment.getApplication(),
            fakeSettings,
            fakeApi,
            fakeScheduler,
            SavedStateHandle(),
            setter
        )

        vm.setWallpaperNow()

        assertThat(setter.calls).isEqualTo(1)
        assertThat(fakeApi.todayImageCalls).isEqualTo(1)
        assertThat(vm.uiState.value.lastUpdated).isNull()
    }
}
