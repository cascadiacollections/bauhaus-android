package com.cascadiacollections.bauhaus.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val json = Json { ignoreUnknownKeys = true }

/** Image format negotiation header shared by [BauhausApi] and [HttpModule]. */
internal const val IMAGE_ACCEPT_HEADER = "image/avif, image/webp, image/jpeg"

/**
 * Metadata returned by the bauhaus CDN for a given day's artwork.
 *
 * All fields default to empty strings so deserialization never fails on
 * missing keys — the CDN may omit fields for older archive entries.
 */
@Serializable
data class ArtworkMetadata(
    val title: String = "",
    val artist: String = "",
    val source: String = "",
    val license: String = "",
    val date: String = "",
)

/**
 * Client for the bauhaus Cloudflare Workers CDN.
 *
 * ## Endpoints
 *
 * | Route | Returns |
 * |-------|---------|
 * | `GET /api/today` | Today's stylized image (content-negotiated) |
 * | `GET /api/today.json` | Today's [ArtworkMetadata] |
 * | `GET /api/YYYY-MM-DD` | Archive image (immutable cache) |
 *
 * ## Format Negotiation
 *
 * The `Accept` header requests AVIF > WebP > JPEG. The CDN picks the best
 * pre-generated variant and falls back to JPEG when others are unavailable.
 *
 * ## Caching & COGs
 *
 * Pass the shared [OkHttpClient] from [HttpModule] so that the disk cache
 * deduplicates requests. `/api/today` responses are cached for 5 minutes
 * (`max-age=300`), so opening the app, previewing the image, and tapping
 * "Set Now" in quick succession costs at most **one** CDN request.
 *
 * @param client Shared [OkHttpClient] with disk cache — obtain via [HttpModule.client].
 */
open class BauhausApi(private val client: OkHttpClient) : BauhausApiClient {

    companion object {
        const val BASE_URL = "https://bauhaus.cascadiacollections.workers.dev"
        private val ISO_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
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
     * @throws IllegalStateException if the CDN response cannot be decoded.
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
        imagePath = "/api/${date.format(ISO_DATE_FORMAT)}",
        maxWidth = maxWidth,
        maxHeight = maxHeight,
    )

    private suspend fun fetchImageForPath(
        imagePath: String,
        maxWidth: Int,
        maxHeight: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        val endpoint = imagePath
        val request = Request.Builder()
            .url("$BASE_URL$imagePath")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        val bytes = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, endpoint)
                val body = response.body ?: throw BauhausEmptyBodyException(endpoint)
                body.bytes()
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(endpoint, e)
        }

        decodeSampled(bytes, maxWidth, maxHeight)
    }

    /**
     * Fetches today's artwork as raw bytes, preserving the original format
     * (AVIF, WebP, or JPEG) negotiated by the CDN.
     *
     * @return The image bytes paired with the MIME type from the `Content-Type` header.
     */
    override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        fetchImageRawForPath("/api/today")
    }

    override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        fetchImageRawForPath("/api/${date.format(ISO_DATE_FORMAT)}")
    }

    private fun fetchImageRawForPath(path: String): Pair<ByteArray, String> {
        val endpoint = path
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, endpoint)
                val body = response.body ?: throw BauhausEmptyBodyException(endpoint)
                val mimeType = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "image/jpeg"
                val bytes = body.bytes()
                bytes to mimeType
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(endpoint, e)
        }
    }

    /**
     * Fetches today's artwork metadata (title, artist, source, license).
     *
     * This is a lightweight JSON call (~200 bytes) and is safe to call on
     * every app open. The CDN caches the response for 5 minutes.
     */
    override suspend fun fetchTodayMetadata(): ArtworkMetadata = fetchMetadataForPath("/api/today.json")

    override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata =
        fetchMetadataForPath("/api/${date.format(ISO_DATE_FORMAT)}.json")

    private suspend fun fetchMetadataForPath(path: String): ArtworkMetadata = withContext(Dispatchers.IO) {
        val endpoint = path
        val request = Request.Builder()
            .url("$BASE_URL$path")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw BauhausHttpException(response.code, endpoint)
                val body = response.body ?: throw BauhausEmptyBodyException(endpoint)
                try {
                    json.decodeFromString<ArtworkMetadata>(body.string())
                } catch (e: Exception) {
                    throw BauhausDecodeException(endpoint, e)
                }
            }
        } catch (e: BauhausDataException) {
            throw e
        } catch (e: IOException) {
            throw BauhausNetworkException(endpoint, e)
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

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

    options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)
    options.inJustDecodeBounds = false

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
