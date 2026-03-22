package com.cascadiacollections.bauhaus.data

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Singleton HTTP client shared across the app: [BauhausApi], Coil image loader,
 * and [com.cascadiacollections.bauhaus.worker.WallpaperWorker].
 *
 * ## Caching & COGs
 *
 * The bauhaus CDN returns `Cache-Control: public, max-age=300` for `/api/today*`
 * endpoints. OkHttp's disk cache respects these headers, so repeated requests within
 * the 5-minute window (e.g. opening the app twice, or the preview image + "Set Now"
 * in the same session) are served from local disk at zero CDN cost.
 *
 * Date-specific endpoints (`/api/YYYY-MM-DD`) return immutable cache headers
 * (1-year max-age), so archived artwork is fetched exactly once.
 */
object HttpModule {

    private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB

    private var instance: OkHttpClient? = null

    /**
     * Returns the shared [OkHttpClient] instance, creating it on first access.
     *
     * @param context Application context, used to resolve the HTTP cache directory.
     *                Only read on first call; subsequent calls return the cached instance.
     */
    fun client(context: Context): OkHttpClient =
        instance ?: synchronized(this) {
            instance ?: OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "http_cache"), CACHE_SIZE_BYTES))
                .connectTimeout(15.seconds.toJavaDuration())
                .readTimeout(15.seconds.toJavaDuration())
                .writeTimeout(15.seconds.toJavaDuration())
                .build()
                .also { instance = it }
        }
}
