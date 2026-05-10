package com.cascadiacollections.bauhaus.worker

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WallpaperWorkerTest {
    @Test
    fun `worker skips fetch when already updated today`() = runTest {
        val api = FakeApi()
        val settings = FakeSettings(lastUpdated = LocalDate.now().toString())
        val worker = buildWorker(settings, api)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(api.fetchCalled)
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

    private fun buildWorker(
        settings: SettingsStore,
        api: BauhausApiClient,
        runAttemptCount: Int = 0,
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
            .build()
    }

    private class FakeSettings(lastUpdated: String?) : SettingsStore {
        override val wallpaperTarget: Flow<WallpaperTarget> = MutableStateFlow(WallpaperTarget.BOTH)
        override val schedulingEnabled: Flow<Boolean> = MutableStateFlow(true)
        override val lastUpdated: Flow<String?> = MutableStateFlow(lastUpdated)

        override suspend fun isFirstRun(): Boolean = false
        override suspend fun setWallpaperTarget(target: WallpaperTarget) = Unit
        override suspend fun setSchedulingEnabled(enabled: Boolean) = Unit
        override suspend fun setLastUpdated(date: String) = Unit
        override suspend fun markFirstRunComplete() = Unit
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
    }
}
