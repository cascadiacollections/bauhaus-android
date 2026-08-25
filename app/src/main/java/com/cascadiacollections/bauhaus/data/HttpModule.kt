package com.cascadiacollections.bauhaus.data

import android.content.Context
import okhttp3.Cache
import okhttp3.HttpUrl
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
 * The bauhaus service returns, for `/api/today*`:
 *
 * ```
 * Cache-Control: public, max-age=300, s-maxage=3600, stale-while-revalidate=604800
 * Vary: Accept
 * ETag: "<r2 object etag>"
 * ```
 *
 * OkHttp honours `max-age` and `Vary`, so repeated requests inside the 5-minute
 * window are served from local disk at zero service cost. It ignores `s-maxage`
 * and `stale-while-revalidate` (both are for shared caches), which is correct
 * here: past 5 minutes OkHttp revalidates with `If-None-Match` and the service
 * answers `304` with no body.
 *
 * An application-level interceptor ensures **every** image request to the service
 * carries the same `Accept: image/avif, image/webp, image/jpeg` header — including
 * Coil preview loads. This is critical because `Vary: Accept` means requests with
 * different `Accept` headers produce different cache keys. Without this
 * interceptor, Coil (no `Accept` header) and [BauhausApi] (explicit header) would
 * cache-miss each other, doubling service requests.
 *
 * Date-specific routes (`/api/YYYY-MM-DD*`) return
 * `public, max-age=31536000, s-maxage=31536000, immutable`, which is safe because
 * publishing is write-once — the pipeline refuses to rewrite a date. Archived
 * artwork is therefore fetched exactly once per device.
 */
object HttpModule {

    private const val CACHE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
    private const val CDN_HOST = "bauhaus.cascadiacollections.workers.dev"

    /**
     * Suffixes served as something other than an image. `Accept` negotiation
     * does not apply to these, and the service does not `Vary` on it for them.
     *
     * `.sig` matters as well as `.json`: `/api/<date>.json.sig` is a detached PGP
     * signature, and testing only for a `.json` suffix would tag it as an image.
     */
    private val NON_IMAGE_SUFFIXES = listOf(".json", ".sig")

    private fun HttpUrl.isServiceImageRequest(): Boolean =
        host == CDN_HOST && NON_IMAGE_SUFFIXES.none { encodedPath.endsWith(it) }

    /**
     * Interceptor that injects the `Accept` header for image format negotiation
     * on all image requests to the bauhaus service. Ensures Coil and [BauhausApi]
     * produce identical cache keys (since the service returns `Vary: Accept`).
     */
    private val formatNegotiationInterceptor = Interceptor { chain ->
        val request = chain.request()
        if (request.url.isServiceImageRequest()) {
            chain.proceed(
                request.newBuilder()
                    .header("Accept", IMAGE_ACCEPT_HEADER)
                    .build()
            )
        } else {
            chain.proceed(request)
        }
    }

    @Volatile
    private var instance: OkHttpClient? = null

    /**
     * Returns the shared [OkHttpClient] instance, creating it on first access.
     *
     * OkHttp's disk [Cache] requires exactly one live instance per directory, so
     * this must actually memoize rather than build a fresh client per call — a
     * second instance over the same `http_cache` directory can corrupt the cache
     * journal and silently defeat the `Vary: Accept` cache-key sharing described
     * above.
     *
     * @param context Application context, used to resolve the HTTP cache directory.
     *                Only read on first call; subsequent calls return the cached instance.
     */
    fun create(context: Context): OkHttpClient = instance ?: synchronized(this) {
        instance ?: OkHttpClient.Builder()
            .cache(Cache(File(context.applicationContext.cacheDir, "http_cache"), CACHE_SIZE_BYTES))
            .addInterceptor(formatNegotiationInterceptor)
            .connectTimeout(15.seconds.toJavaDuration())
            .readTimeout(15.seconds.toJavaDuration())
            .writeTimeout(15.seconds.toJavaDuration())
            .build()
            .also { instance = it }
    }
}
