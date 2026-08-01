package com.cascadiacollections.bauhaus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val json = Json { ignoreUnknownKeys = true }

/** Image format negotiation header shared by [BauhausApi] and [HttpModule]. */
internal const val IMAGE_ACCEPT_HEADER = "image/avif, image/webp, image/jpeg"

private const val HTTP_NOT_FOUND = 404
private const val HTTP_UNAVAILABLE = 503

/**
 * Client for the bauhaus Cloudflare Workers service.
 *
 * ## Endpoints used
 *
 * | Route | Returns |
 * |-------|---------|
 * | `GET /api/today` | Today's stylized image (content-negotiated) |
 * | `GET /api/today.json` | Today's [ArtworkMetadata] |
 * | `GET /api/YYYY-MM-DD` | Archive image (immutable cache) |
 * | `GET /api/YYYY-MM-DD.json` | Archive [ArtworkMetadata] |
 * | `HEAD /api/YYYY-MM-DD.json` | Existence probe — see [hasArtworkForDate] |
 * | `GET /api/health` | Publish freshness — see [fetchHealth] |
 *
 * ## Format Negotiation
 *
 * The `Accept` header requests AVIF > WebP > JPEG. The service picks the best
 * pre-generated variant and falls back to JPEG when others are unavailable.
 * The `?format=` override is deliberately not used: it rejects unknown values
 * with `400`, and `Accept` already expresses everything the app needs.
 *
 * ## Caching & COGs
 *
 * Pass the shared [OkHttpClient] from [HttpModule] so that the disk cache
 * deduplicates requests. `/api/today*` responses carry `max-age=300`, so opening
 * the app, previewing the image, and tapping "Set Now" in quick succession costs
 * at most **one** service request. Every response also carries an `ETag`, so once
 * the 5-minute window lapses OkHttp revalidates conditionally and the service
 * answers `304` without re-sending the body.
 *
 * Date-keyed routes are `immutable` with a one-year TTL, so archived artwork is
 * fetched exactly once per device.
 *
 * @param client Shared [OkHttpClient] with disk cache — obtain via [HttpModule.create].
 */
open class BauhausApi(private val client: OkHttpClient) : BauhausApiClient {

    companion object {
        const val BASE_URL = "https://bauhaus.cascadiacollections.workers.dev"
        private val ISO_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** Path of the stylized image for [date]. */
        fun imagePath(date: LocalDate): String = "/api/${date.format(ISO_DATE_FORMAT)}"

        /** Path of the metadata document for [date]. */
        fun metadataPath(date: LocalDate): String = "${imagePath(date)}.json"
    }

    /**
     * Fetches today's artwork as a [Bitmap], optionally downsampled to fit
     * within [maxWidth] x [maxHeight] pixels.
     *
     * Downsampling prevents OOM on high-resolution source images and reduces
     * memory pressure — especially important in the background [WallpaperWorker][com.cascadiacollections.bauhaus.worker.WallpaperWorker]
     * where there is no UI to reclaim memory from.
     *
     * @param maxWidth  Target width in pixels (0 = no downsampling).
     * @param maxHeight Target height in pixels (0 = no downsampling).
     * @return Decoded bitmap, sized to fit within the requested bounds.
     * @throws IllegalStateException if the response cannot be decoded.
     */
    override suspend fun fetchTodayImage(
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap = fetchImageForPath(
        imagePath = "/api/today",
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    override suspend fun fetchImageForDate(
        date: LocalDate,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap = fetchImageForPath(
        imagePath = imagePath(date),
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    private suspend fun fetchImageForPath(
        imagePath: String,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL$imagePath")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        val bytes = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, imagePath)
                val body = response.body
                body.bytes()
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(imagePath, e)
        }

        decodeSampled(bytes, maxWidth, maxHeight)
    }

    /**
     * Fetches today's artwork as raw bytes, preserving the original format
     * (AVIF, WebP, or JPEG) negotiated by the service.
     *
     * @return The image bytes paired with the MIME type from the `Content-Type` header.
     */
    override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        fetchImageRawForPath("/api/today")
    }

    override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        fetchImageRawForPath(imagePath(date))
    }

    private fun fetchImageRawForPath(path: String): Pair<ByteArray, String> {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, path)
                val body = response.body
                val mimeType = response.header("Content-Type")
                    ?.substringBefore(";")
                    ?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: "image/jpeg"
                val bytes = body.bytes()
                bytes to mimeType
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(path, e)
        }
    }

    /**
     * Fetches today's artwork metadata.
     *
     * This is a lightweight JSON call and is safe to call on every app open. The
     * service caches the response for 5 minutes and serves an `ETag`, so repeat
     * calls are either a local cache hit or a `304`.
     */
    override suspend fun fetchTodayMetadata(): ArtworkMetadata = fetchMetadataForPath("/api/today.json")

    override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata =
        fetchMetadataForPath(metadataPath(date))

    private suspend fun fetchMetadataForPath(path: String): ArtworkMetadata = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, path)
                val body = response.body
                try {
                    json.decodeFromString<ArtworkMetadata>(body.string())
                } catch (e: Exception) {
                    throw BauhausDecodeException(path, e)
                }
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(path, e)
        }
    }

    /**
     * Probes whether the service has published artwork for [date], without
     * transferring a body.
     *
     * Uses `HEAD`, which the service answers with the same headers as `GET` and
     * resolves with a metadata-only storage lookup. That matters for the archive
     * pager: establishing that a date exists used to cost a full metadata `GET`
     * per candidate day, so a two-year jump issued one request per day in the span.
     *
     * @return `true` for `200`, `false` for `404`.
     * @throws BauhausHttpException for any other status.
     * @throws BauhausNetworkException if the request cannot be completed.
     */
    override suspend fun hasArtworkForDate(date: LocalDate): Boolean = withContext(Dispatchers.IO) {
        val path = metadataPath(date)
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .head()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == HTTP_NOT_FOUND -> false
                    else -> throw BauhausHttpException(response.code, path)
                }
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(path, e)
        }
    }

    /**
     * Reads `GET /api/health` — the service's own view of whether it is current.
     *
     * The endpoint answers `503` for both "stale" and "unhealthy", and both of
     * those carry a useful JSON body, so a non-2xx status is *not* an error here.
     * Only a status outside {200, 503} or an unparseable body is.
     *
     * `Cache-Control: no-store` on this route means every call reaches the
     * service, so it is used only after something else has already failed —
     * never on the startup path.
     */
    override suspend fun fetchHealth(): ServiceHealth = withContext(Dispatchers.IO) {
        val path = "/api/health"
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != HTTP_UNAVAILABLE) {
                    throw BauhausHttpException(response.code, path)
                }
                val body = response.body.string()
                try {
                    json.decodeFromString<ServiceHealth>(body)
                } catch (e: Exception) {
                    throw BauhausDecodeException(path, e)
                }
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(path, e)
        }
    }
}

/**
 * Decodes [bytes] into a [Bitmap], downsampling if [maxWidth]/[maxHeight] > 0.
 *
 * Uses a two-pass approach: first decode bounds only, then compute an
 * appropriate `inSampleSize` power-of-two and decode at reduced resolution.
 */
private fun decodeSampled(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap {
    if (maxWidth <= 0 || maxHeight <= 0) {
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Failed to decode image from ${bytes.size} bytes"
        }
    }

    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

    options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
    options.inJustDecodeBounds = false
    options.inPreferredConfig = Bitmap.Config.RGB_565

    return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
        "Failed to decode image from ${bytes.size} bytes with sample size ${options.inSampleSize}"
    }
}

/**
 * Computes the largest power-of-two `inSampleSize` such that the decoded
 * dimensions are still >= [reqWidth] x [reqHeight].
 */
private fun calculateInSampleSize(
    rawWidth: Int,
    rawHeight: Int,
    reqWidth: Int,
    reqHeight: Int,
): Int {
    var inSampleSize = 1
    if (rawHeight > reqHeight || rawWidth > reqWidth) {
        val halfHeight = rawHeight / 2
        val halfWidth = rawWidth / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}
