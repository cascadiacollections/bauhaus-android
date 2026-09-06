package com.cascadiacollections.bauhaus

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.cascadiacollections.bauhaus.data.serviceToday
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

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
class BauhausApplication : Application(), AppContainerProvider, SingletonImageLoader.Factory, Configuration.Provider {

    companion object {
        private const val TAG = "BauhausApplication"
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            runCatching {
                val watcherClass = Class.forName("leakcanary.AppWatcher")
                val configClass = Class.forName("leakcanary.AppWatcher\$Config")
                val config = watcherClass.getMethod("getConfig").invoke(null)
                val copyMethod = configClass.getMethod(
                    "copy",
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
                val customConfig = copyMethod.invoke(
                    config,
                    true,
                    false,
                    false,
                    true,
                    5_000L,
                    true,
                )
                watcherClass.getMethod("setConfig", configClass).invoke(null, customConfig)
            }
        }
        container = AppContainer(this)
        CrashReporter.init(this)
        scheduleWallpaperWorkerIfEnabled()
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
 *
 * Hardware bitmaps are left enabled: nothing reads pixels back out of a Coil
 * result. The wallpaper path decodes its own software bitmap in
 * [BauhausApi][com.cascadiacollections.bauhaus.data.BauhausApi], and saving to
 * the gallery writes the original bytes without decoding at all.
     */
    override fun newImageLoader(context: coil3.PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { container.okHttpClient },
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

    /** Only schedules the worker if the user has not disabled daily updates. */
    private fun scheduleWallpaperWorkerIfEnabled() {
        appScope.launch {
            val enabled = container.settingsRepository.schedulingEnabled.first()
            if (enabled) {
                scheduleWallpaperWorker()
            }
        }
    }

    /**
     * On the very first launch, requests an immediate wallpaper update so the
     * wallpaper is set right away instead of waiting for the periodic window.
     *
     * Goes through [WallpaperScheduler.requestImmediateUpdate], which enqueues
     * expedited unique work with a non-expedited fallback for when the system's
     * expedited quota is exhausted.
     */
    private fun enqueueFirstRunIfNeeded() {
        val settings = container.settingsRepository
        appScope.launch {
            if (!settings.isFirstRun()) return@launch

            container.wallpaperScheduler.requestImmediateUpdate()
            settings.markFirstRunComplete()
        }
    }

    /**
     * Opportunistically warms the `/api/today` cache once per day so the first
     * foreground render can hit local cache on the happy path.
     *
     * Skips when the wallpaper was already set today (image already cached) or
     * when prefetch already ran today.
     */
    private fun prefetchTodayImageIfNeeded() {
        val settings = container.settingsRepository
        val api = container.bauhausApi
        appScope.launch {
            val today = serviceToday().toString()
            val lastUpdated = settings.lastUpdated.first()
            if (lastUpdated == today) return@launch
            if (settings.getLastPrefetchedDate() == today) return@launch
            try {
                api.fetchTodayImageRaw()
                settings.setLastPrefetchedDate(today)
                AppLogger.info(
                    TAG,
                    AppLogger.Event("startup_prefetch_success", mapOf("date" to today)),
                    "Prefetched today's image into cache",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort cache warming: a miss just means the first render
                // goes to the network. Never escalated beyond a warning.
                AppLogger.warn(
                    TAG,
                    AppLogger.Event("startup_prefetch_failure", mapOf("date" to today)),
                    "Failed to prefetch today's image: ${e.message}",
                )
            }
        }
    }
}
