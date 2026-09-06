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
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.ServiceHealth
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import com.cascadiacollections.bauhaus.data.serviceToday
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.time.LocalDate

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

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(api.fetchCalled)
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

        assertTrue(api.fetchCalled)
    }

    @Test
    fun `worker retries on fetch failure`() = runTest {
        val api = FakeApi(shouldThrow = true)
        val settings = FakeSettings(lastUpdated = "2000-01-01")
        val worker = buildWorker(settings, api)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `worker fails when retries exhausted`() = runTest {
        val api = FakeApi(shouldThrow = true)
        val settings = FakeSettings(lastUpdated = "2000-01-01")
        val worker = buildWorker(settings, api, runAttemptCount = 3)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
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
            userInitiated = true,
        )

        worker.doWork()

        assertEquals(1, postedNotifications().size)
    }

    @Test
    fun `the daily schedule gives up silently`() = runTest {
        // The scheduled job runs forever in the background. It has no standing
        // to interrupt anyone over a failure they did not ask to watch.
        val worker = buildWorker(
            FakeSettings(lastUpdated = "2000-01-01"),
            FakeApi(shouldThrow = true),
            runAttemptCount = 3,
        )

        worker.doWork()

        assertTrue(postedNotifications().isEmpty())
    }

    private fun postedNotifications() =
        shadowOf(
            RuntimeEnvironment.getApplication()
                .getSystemService(NotificationManager::class.java),
        ).allNotifications

    private fun buildWorker(
        settings: SettingsStore,
        api: BauhausApiClient,
        runAttemptCount: Int = 0,
        userInitiated: Boolean = false,
    ): WallpaperWorker {
        val dependencies = WallpaperWorker.Dependencies(settings, api)
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                return WallpaperWorker(
                    context = appContext,
                    params = workerParameters,
                    dependencies = dependencies,
                )
            }
        }

        val testContext: Context = RuntimeEnvironment.getApplication()
        return TestListenableWorkerBuilder<WallpaperWorker>(testContext)
            .setWorkerFactory(factory)
            .setRunAttemptCount(runAttemptCount)
            .setInputData(workDataOf(WallpaperWorker.KEY_USER_INITIATED to userInitiated))
            .build()
    }

    private class FakeSettings(lastUpdated: String?) : SettingsStore {
        override val wallpaperTarget: Flow<WallpaperTarget> = MutableStateFlow(WallpaperTarget.BOTH)
        override val schedulingEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val lastUpdated: Flow<String?> = MutableStateFlow(lastUpdated)
        override val favorites: Flow<Set<String>> = MutableStateFlow(emptySet())

        override suspend fun isFirstRun(): Boolean = false
        override suspend fun setWallpaperTarget(target: WallpaperTarget) = Unit
        override suspend fun setSchedulingEnabled(enabled: Boolean) = Unit
        override suspend fun setLastUpdated(date: String) = Unit
        override suspend fun getLastPrefetchedDate(): String? = null
        override suspend fun setLastPrefetchedDate(date: String) = Unit
        override suspend fun markFirstRunComplete() = Unit
        override suspend fun toggleFavorite(date: String) = Unit
    }

    private class FakeApi(
        private val shouldThrow: Boolean = false,
    ) : BauhausApiClient {
        var fetchCalled = false

        override suspend fun fetchTodayImage(maxWidth: Int, maxHeight: Int): Bitmap {
            fetchCalled = true
            if (shouldThrow) throw RuntimeException("boom")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> {
            if (shouldThrow) throw RuntimeException("boom")
            return byteArrayOf(1) to "image/jpeg"
        }

        override suspend fun fetchTodayMetadata(): ArtworkMetadata {
            if (shouldThrow) throw RuntimeException("boom")
            return ArtworkMetadata()
        }

        override suspend fun fetchImageForDate(date: LocalDate, maxWidth: Int, maxHeight: Int): Bitmap {
            if (shouldThrow) throw RuntimeException("boom")
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> {
            if (shouldThrow) throw RuntimeException("boom")
            return byteArrayOf(1) to "image/jpeg"
        }

        override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata {
            if (shouldThrow) throw RuntimeException("boom")
            return ArtworkMetadata(date = date.toString())
        }

        override suspend fun hasArtworkForDate(date: LocalDate): Boolean {
            if (shouldThrow) throw RuntimeException("boom")
            return true
        }

        override suspend fun fetchHealth(): ServiceHealth =
            ServiceHealth(status = ServiceHealth.STATUS_OK, date = serviceToday().toString())
    }
}
