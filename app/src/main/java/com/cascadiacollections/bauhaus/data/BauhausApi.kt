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
open class BauhausApi(private val client: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://bauhaus.cascadiacollections.workers.dev"
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
    open suspend fun fetchTodayImage(
        maxWidth: Int = 0,
        maxHeight: Int = 0,
    ): Bitmap = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/today")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        val bytes = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CDN returned HTTP ${response.code}")
            response.body.bytes()
        }

        decodeSampled(bytes, maxWidth, maxHeight)
    }

    /**
     * Fetches today's artwork as raw bytes, preserving the original format
     * (AVIF, WebP, or JPEG) negotiated by the CDN.
     *
     * @return The image bytes paired with the MIME type from the `Content-Type` header.
     */
    open suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/today")
            .header("Accept", IMAGE_ACCEPT_HEADER)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CDN returned HTTP ${response.code}")
            val mimeType = response.header("Content-Type")?.substringBefore(";")?.trim() ?: "image/jpeg"
            val bytes = response.body.bytes()
            bytes to mimeType
        }
    }

    /**
     * Fetches today's artwork metadata (title, artist, source, license).
     *
     * This is a lightweight JSON call (~200 bytes) and is safe to call on
     * every app open. The CDN caches the response for 5 minutes.
     */
    open suspend fun fetchTodayMetadata(): ArtworkMetadata = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$BASE_URL/api/today.json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("CDN returned HTTP ${response.code}")
            json.decodeFromString<ArtworkMetadata>(response.body.string())
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
