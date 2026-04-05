package com.cascadiacollections.bauhaus

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

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
 */
class BauhausApplication : Application(), SingletonImageLoader.Factory {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        CrashReporter.init(this)
        scheduleWallpaperWorker()
        enqueueFirstRunIfNeeded()
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
        val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)

        val workRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(
            repeatInterval = 24.hours.toJavaDuration(),
            flexTimeInterval = 1.hours.toJavaDuration(),
        )
            .setConstraints(constraints)
            .addTag(WallpaperWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WallpaperWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    /** Cancels the daily periodic worker (called when the user disables scheduling). */
    fun cancelWallpaperWorker() {
        WorkManager.getInstance(this).cancelUniqueWork(WallpaperWorker.WORK_NAME)
    }

    /**
     * On the very first launch, enqueues an expedited one-time worker so the
     * wallpaper is set immediately instead of waiting for the periodic window.
     *
     * Uses [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST] as a fallback
     * if the system's expedited quota is exhausted.
     */
    private fun enqueueFirstRunIfNeeded() {
        val settings = SettingsRepository(this)
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
}
