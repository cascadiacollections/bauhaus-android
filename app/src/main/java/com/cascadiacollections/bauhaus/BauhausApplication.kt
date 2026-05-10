package com.cascadiacollections.bauhaus

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Application entry point. Responsible for:
 *
 * 1. **WorkManager scheduling** — enqueues a daily [WallpaperWorker] that fetches
 *    today's artwork from the bauhaus CDN and sets it as the device wallpaper.
 * 2. **Expedited first-run** — on the very first launch, enqueues a one-time
 *    expedited worker so the wallpaper is set immediately rather than waiting
 *    up to 24 hours.
 * 3. **Coil singleton** — configures a shared [ImageLoader] backed by the same
 *    [OkHttpClient][okhttp3.OkHttpClient] (with disk cache) used by [BauhausApi][com.cascadiacollections.bauhaus.data.BauhausApi],
 *    so the preview image in [SettingsScreen][com.cascadiacollections.bauhaus.ui.SettingsScreen]
 *    and the worker share cached responses. This directly reduces CDN COGs.
 * 4. **Startup prefetch** — opportunistically fetches today's image once per day
 *    to warm HTTP cache before UI render.
 */
class BauhausApplication : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    companion object {
        private const val TAG = "BauhausApplication"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CrashReporter.init(this)
        scheduleWallpaperWorker()
        enqueueFirstRunIfNeeded()
        prefetchTodayImageIfNeeded()
    }

    override val workManagerConfiguration: Configuration by lazy {
        Configuration.Builder()
            .setWorkerFactory(container.workerFactory)
            .build()
    }

    // -- Coil SingletonImageLoader.Factory --

    /**
     * Provides the app-wide [ImageLoader] for Coil's `AsyncImage`.
     *
     * Uses the shared [HttpModule] client so that:
     * - AVIF/WebP format negotiation works via the `Accept` header interceptor
     * - OkHttp disk cache is shared (5-min TTL for `/api/today`)
     * - Memory cache uses Coil's default (25 % of heap — plenty for one image)
     */
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { HttpModule.client(this@BauhausApplication) },
                    ),
                )
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()

    // -- WorkManager --

    /** Enqueues (or keeps) the daily periodic wallpaper worker. */
    fun scheduleWallpaperWorker() {
        container.wallpaperScheduler.scheduleDaily()
    }

    /** Cancels the daily periodic worker (called when the user disables scheduling). */
    fun cancelWallpaperWorker() {
        container.wallpaperScheduler.cancelDaily()
    }

    /**
     * On the very first launch, enqueues an expedited one-time worker so the
     * wallpaper is set immediately instead of waiting for the periodic window.
     *
     * Uses [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] as a fallback
     * if the system's expedited quota is exhausted.
     */
    private fun enqueueFirstRunIfNeeded() {
        val settings = container.settingsRepository
        appScope.launch {
            if (!settings.isFirstRun()) return@launch

            val expedited = OneTimeWorkRequestBuilder<WallpaperWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WallpaperWorker.TAG)
                .build()

            WorkManager.getInstance(this@BauhausApplication).enqueue(expedited)
            settings.markFirstRunComplete()
        }
    }

    /**
     * Opportunistically warms the `/api/today` cache once per day so the first
     * foreground render can hit local cache on the happy path.
     */
    private fun prefetchTodayImageIfNeeded() {
        val settings = container.settingsRepository
        val api = container.bauhausApi
        appScope.launch {
            val today = LocalDate.now().toString()
            if (settings.getLastPrefetchedDate() == today) return@launch
            try {
                api.fetchTodayImageRaw()
                settings.setLastPrefetchedDate(today)
                AppLogger.info(
                    TAG,
                    AppLogger.Event("startup_prefetch_success", mapOf("date" to today)),
                    "Prefetched today's image into cache",
                )
            } catch (e: Exception) {
                AppLogger.warn(
                    TAG,
                    AppLogger.Event("startup_prefetch_failure", mapOf("date" to today)),
                    "Failed to prefetch today's image",
                )
            }
        }
    }
}
