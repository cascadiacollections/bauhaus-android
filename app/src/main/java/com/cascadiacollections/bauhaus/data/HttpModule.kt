package com.cascadiacollections.bauhaus.data

import android.content.Context
import okhttp3.Cache
import okhttp3.Interceptor
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
 * endpoints and `Vary: Accept`. OkHttp's disk cache respects both, so repeated
 * requests within the 5-minute window are served from local disk at zero CDN cost.
 *
 * An application-level interceptor ensures **every** request to the CDN carries
 * the same `Accept: image/avif, image/webp, image/jpeg` header — including
 * Coil preview loads. This is critical because `Vary: Accept` means requests
 * with different `Accept` headers produce different cache keys. Without this
 * interceptor, Coil (no `Accept` header) and [BauhausApi] (explicit header)
 * would cache-miss each other, doubling CDN requests.
 *
 * Date-specific endpoints (`/api/YYYY-MM-DD`) return immutable cache headers
 * (1-year max-age), so archived artwork is fetched exactly once.
 */
object HttpModule {

    private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
    private const val CDN_HOST = "bauhaus.cascadiacollections.workers.dev"

    private var instance: OkHttpClient? = null

    /**
     * Interceptor that injects the `Accept` header for image format negotiation
     * on all requests to the bauhaus CDN. Ensures Coil and [BauhausApi] produce
     * identical cache keys (since the CDN returns `Vary: Accept`).
     */
    private val formatNegotiationInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.host == CDN_HOST && !request.url.encodedPath.endsWith(".json")) {
            chain.proceed(
                request.newBuilder()
                    .header("Accept", IMAGE_ACCEPT_HEADER)
                    .build()
            )
        } else {
            chain.proceed(request)
        }
    }

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
                .addInterceptor(formatNegotiationInterceptor)
                .connectTimeout(15.seconds.toJavaDuration())
                .readTimeout(15.seconds.toJavaDuration())
                .writeTimeout(15.seconds.toJavaDuration())
                .build()
                .also { instance = it }
        }
}
