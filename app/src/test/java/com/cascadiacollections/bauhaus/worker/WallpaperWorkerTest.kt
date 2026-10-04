package com.cascadiacollections.bauhaus.worker

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.cascadiacollections.bauhaus.data.ArchiveIndexPage
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausDecodeException
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.BauhausNetworkException
import com.cascadiacollections.bauhaus.data.ServiceHealth
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperSetter
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.serviceToday
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WallpaperWorkerTest {
    @Test
    fun `worker skips fetch when already updated today`() = runTest {
        // serviceToday(), not LocalDate.now(): the worker's skip guard is keyed to
        // the service's UTC day so it agrees with what the ViewModel stamps.
        val api = FakeApi()
        val settings = FakeSettings(lastUpdated = serviceToday().toString())
        val worker = buildWorker(settings, api)

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(api.fetchCalled).isFalse()
    }

    @Test
    fun `worker does not skip when lastUpdated names a day the service is not on`() = runTest {
        // A device east of UTC can have stamped tomorrow's local date. That must not
        // read as "today is already done". shouldThrow keeps the test off the
        // WallpaperManager path — reaching the fetch is the whole assertion.
        val api = FakeApi(shouldThrow = true)
        val settings = FakeSettings(lastUpdated = serviceToday().plusDays(1).toString())
        val worker = buildWorker(settings, api)

        worker.doWork()

        assertThat(api.fetchCalled).isTrue()
    }

    @Test
    fun `worker retries on fetch failure`() = runTest {
        val api = FakeApi(shouldThrow = true)
        val settings = FakeSettings(lastUpdated = "2000-01-01")
        val worker = buildWorker(settings, api)

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `worker fails when retries exhausted`() = runTest {
        val api = FakeApi(shouldThrow = true)
        val settings = FakeSettings(lastUpdated = "2000-01-01")
        val worker = buildWorker(settings, api, runAttemptCount = 3)

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `a user-initiated run that gives up notifies`() = runTest {
        // A tile tap that silently achieves nothing is worse than no tile.
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val worker = buildWorker(
            FakeSettings(lastUpdated = "2000-01-01"),
            FakeApi(shouldThrow = true),
            runAttemptCount = 3,
            userInitiated = true
        )

        worker.doWork()

        assertThat(postedNotifications()).hasSize(1)
    }

    @Test
    fun `the daily schedule gives up silently`() = runTest {
        // The scheduled job runs forever in the background. It has no standing
        // to interrupt anyone over a failure they did not ask to watch.
        val worker = buildWorker(
            FakeSettings(lastUpdated = "2000-01-01"),
            FakeApi(shouldThrow = true),
            runAttemptCount = 3
        )

        worker.doWork()

        assertThat(postedNotifications()).isEmpty()
    }

    // ── which day gets stamped ───────────────────────────────────────────────

    @Test
    fun `a run before the day's publish stamps the day it actually applied`() = runTest {
        // The clock says D, but /api/today is still D-1's artwork. Stamping D would
        // make every later run today skip, including the one after publishing.
        val today = serviceToday()
        val yesterday = today.minusDays(1)
        val api = FakeApi(publishedDate = yesterday)
        val settings = FakeSettings(lastUpdated = today.minusDays(2).toString())
        val setter = RecordingSetter()

        val result = buildWorker(settings, api, wallpaperSetter = setter).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(api.imageDates).containsExactly(yesterday)
        assertThat(setter.calls).isEqualTo(1)
        assertThat(settings.lastUpdatedValue).isEqualTo(yesterday.toString())
    }

    @Test
    fun `a run after a pre-publish run fetches again and applies the new day`() = runTest {
        val today = serviceToday()
        val yesterday = today.minusDays(1)
        val api = FakeApi(publishedDate = yesterday)
        val settings = FakeSettings(lastUpdated = today.minusDays(2).toString())
        val setter = RecordingSetter()
        buildWorker(settings, api, wallpaperSetter = setter).doWork()

        // The day's artwork is published between the two runs.
        api.publishedDate = today
        val result = buildWorker(settings, api, wallpaperSetter = setter).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(api.imageDates).containsExactly(yesterday, today)
        assertThat(setter.calls).isEqualTo(2)
        assertThat(settings.lastUpdatedValue).isEqualTo(today.toString())
    }

    @Test
    fun `a run that finds the newest artwork already applied skips the image download`() = runTest {
        val yesterday = serviceToday().minusDays(1)
        val api = FakeApi(publishedDate = yesterday)
        val settings = FakeSettings(lastUpdated = yesterday.toString())
        val setter = RecordingSetter()

        val result = buildWorker(settings, api, wallpaperSetter = setter).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(api.metadataCalls).isEqualTo(1)
        assertThat(api.imageDates).isEmpty()
        assertThat(setter.calls).isEqualTo(0)
    }

    @Test
    fun `metadata that names no publish date applies today's image but stamps nothing`() = runTest {
        val api = FakeApi(publishedDate = null)
        val settings = FakeSettings(lastUpdated = "2000-01-01")
        val setter = RecordingSetter()

        val result = buildWorker(settings, api, wallpaperSetter = setter).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(api.todayImageCalls).isEqualTo(1)
        assertThat(setter.calls).isEqualTo(1)
        assertThat(settings.lastUpdatedValue).isEqualTo("2000-01-01")
    }

    // ── failure classification ───────────────────────────────────────────────

    @Test
    fun `a 4xx from the service fails without retrying`() = runTest {
        val api = FakeApi(imageError = BauhausHttpException(404, "/api/2026-10-04"))

        val result = buildWorker(FakeSettings(lastUpdated = "2000-01-01"), api).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `an undecodable response fails without retrying`() = runTest {
        val api = FakeApi(
            imageError = BauhausDecodeException("/api/2026-10-04", IOException("not an image"))
        )

        val result = buildWorker(FakeSettings(lastUpdated = "2000-01-01"), api).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.failure())
    }

    @Test
    fun `a 5xx from the service is retried`() = runTest {
        val api = FakeApi(imageError = BauhausHttpException(503, "/api/2026-10-04"))

        val result = buildWorker(FakeSettings(lastUpdated = "2000-01-01"), api).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `a connectivity failure is retried`() = runTest {
        val api = FakeApi(
            imageError = BauhausNetworkException("/api/2026-10-04", IOException("Unable to resolve host"))
        )

        val result = buildWorker(FakeSettings(lastUpdated = "2000-01-01"), api).doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test
    fun `a user-initiated run that fails permanently notifies`() = runTest {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val api = FakeApi(imageError = BauhausHttpException(404, "/api/2026-10-04"))

        buildWorker(FakeSettings(lastUpdated = "2000-01-01"), api, userInitiated = true).doWork()

        assertThat(postedNotifications()).hasSize(1)
    }

    private class RecordingSetter : WallpaperSetter {
        var calls = 0

        override suspend fun set(context: Context, bitmap: Bitmap, target: WallpaperTarget) {
            calls++
        }
    }

    private fun postedNotifications() = shadowOf(
        RuntimeEnvironment.getApplication()
            .getSystemService(NotificationManager::class.java)
    ).allNotifications

    private fun buildWorker(
        settings: SettingsStore,
        api: BauhausApiClient,
        runAttemptCount: Int = 0,
        userInitiated: Boolean = false,
        wallpaperSetter: WallpaperSetter = RecordingSetter()
    ): WallpaperWorker {
        val dependencies = WallpaperWorker.Dependencies(settings, api, wallpaperSetter)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker? = WallpaperWorker(
                context = appContext,
                params = workerParameters,
                dependencies = dependencies
            )
        }

        val testContext: Context = RuntimeEnvironment.getApplication()
        return TestListenableWorkerBuilder<WallpaperWorker>(testContext)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(workDataOf(WallpaperWorker.KEY_USER_INITIATED to userInitiated))
            .build()
    }

    private class FakeSettings(lastUpdated: String?) : SettingsStore {
        private val lastUpdatedFlow = MutableStateFlow(lastUpdated)
        val lastUpdatedValue: String? get() = lastUpdatedFlow.value

        override val wallpaperTarget: Flow<WallpaperTarget> = MutableStateFlow(WallpaperTarget.BOTH)
        override val schedulingEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val lastUpdated: Flow<String?> = lastUpdatedFlow
        override val favorites: Flow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun isFirstRun(): Boolean = false
        override suspend fun setWallpaperTarget(target: WallpaperTarget) = Unit
        override suspend fun setSchedulingEnabled(enabled: Boolean) = Unit
        override suspend fun setLastUpdated(date: String) {
            lastUpdatedFlow.value = date
        }
        override suspend fun getLastPrefetchedDate(): String? = null
        override suspend fun setLastPrefetchedDate(date: String) = Unit
        override suspend fun markFirstRunComplete() = Unit
        override suspend fun toggleFavorite(date: String) = Unit
    }

    /**
     * @param shouldThrow Fails every request with a generic error when set.
     * @property publishedDate The day `/api/today.json` says it is serving; `null`
     *   for metadata that names none.
     * @param imageError Thrown by the image fetches when set.
     */
    private class FakeApi(
        private val shouldThrow: Boolean = false,
        var publishedDate: LocalDate? = serviceToday(),
        private val imageError: Exception? = null
    ) : BauhausApiClient {
        /** `true` once any request has been made. */
        var fetchCalled = false
        var metadataCalls = 0
        var todayImageCalls = 0

        /** Dates whose image was fetched through the date-keyed route, in order. */
        val imageDates = mutableListOf<LocalDate>()

        override suspend fun fetchTodayImage(maxWidth: Int, maxHeight: Int): Bitmap {
            fetchCalled = true
            todayImageCalls++
            if (shouldThrow) throw RuntimeException("boom")
            imageError?.let { throw it }
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> {
            fetchCalled = true
            if (shouldThrow) throw RuntimeException("boom")
            return byteArrayOf(1) to "image/jpeg"
        }

        override suspend fun fetchTodayMetadata(): ArtworkMetadata {
            fetchCalled = true
            metadataCalls++
            if (shouldThrow) throw RuntimeException("boom")
            return ArtworkMetadata(date = "ca. 1750", publishedDateRaw = publishedDate?.toString().orEmpty())
        }

        override suspend fun fetchImageForDate(date: LocalDate, maxWidth: Int, maxHeight: Int): Bitmap {
            fetchCalled = true
            imageDates += date
            if (shouldThrow) throw RuntimeException("boom")
            imageError?.let { throw it }
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> {
            fetchCalled = true
            if (shouldThrow) throw RuntimeException("boom")
            return byteArrayOf(1) to "image/jpeg"
        }

        override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata {
            fetchCalled = true
            if (shouldThrow) throw RuntimeException("boom")
            return ArtworkMetadata(publishedDateRaw = date.toString())
        }

        override suspend fun fetchArchivePage(before: LocalDate): ArchiveIndexPage {
            fetchCalled = true
            if (shouldThrow) throw RuntimeException("boom")
            return ArchiveIndexPage(dates = listOf(before.minusDays(1).toString()))
        }

        override suspend fun fetchHealth(): ServiceHealth =
            ServiceHealth(status = ServiceHealth.STATUS_OK, date = serviceToday().toString())
    }
}
