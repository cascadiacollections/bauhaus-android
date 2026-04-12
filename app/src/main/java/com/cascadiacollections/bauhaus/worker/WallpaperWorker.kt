package com.cascadiacollections.bauhaus.worker

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cascadiacollections.bauhaus.CrashReporter
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Background worker that fetches today's bauhaus artwork from the CDN and
 * applies it as the device wallpaper.
 *
 * ## Scheduling
 *
 * Enqueued by [BauhausApplication][com.cascadiacollections.bauhaus.BauhausApplication] as:
 * - A **periodic** request (24 h interval, 1 h flex window) for daily updates.
 * - A one-time **expedited** request on the very first app launch.
 *
 * ## COGs-conscious skip
 *
 * If `lastUpdated` already matches today's date, the worker short-circuits
 * with [Result.success] — no CDN request at all. This handles the case where
 * the user manually tapped "Set Now" earlier in the day, or the worker runs
 * twice within the flex window.
 *
 * ## Retry Policy
 *
 * On failure the worker returns [Result.retry] with exponential backoff
 * (WorkManager default). After [MAX_RETRIES] consecutive failures it returns
 * [Result.failure] to avoid hammering the CDN — this is important for COGs
 * because the CDN owner (you) pays per-request.
 *
 * ## Memory
 *
 * The fetched [Bitmap][android.graphics.Bitmap] is downsampled to the device's
 * screen resolution and explicitly recycled after [WallpaperManager.setBitmap]
 * to free native heap immediately.
 */
class WallpaperWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "WallpaperWorker"
        const val WORK_NAME = "daily_wallpaper"
        private const val MAX_RETRIES = 3
    }

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_RETRIES) {
            Log.w(TAG, "Giving up after $MAX_RETRIES attempts to avoid excessive CDN requests")
            return Result.failure()
        }

        val settings = SettingsRepository(applicationContext)

        // Skip if we already set today's wallpaper (e.g. user tapped "Set Now",
        // or the worker ran twice within the flex window). Saves a CDN request.
        val today = LocalDate.now().toString()
        val lastUpdated = settings.lastUpdated.first()
        if (lastUpdated == today) {
            Log.i(TAG, "Wallpaper already set for $today, skipping CDN fetch")
            return Result.success()
        }

        val api = BauhausApi(HttpModule.client(applicationContext))

        return try {
            val metrics = applicationContext.resources.displayMetrics
            val bitmap = api.fetchTodayImage(
                maxWidth = metrics.widthPixels,
                maxHeight = metrics.heightPixels,
            )

            try {
                val target = settings.wallpaperTarget.first()
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                settings.setLastUpdated(LocalDate.now().toString())
                Log.i(TAG, "Wallpaper set for target: ${target.name}")
                Result.success()
            } finally {
                bitmap.recycle()
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Network error setting wallpaper (attempt ${runAttemptCount + 1}/$MAX_RETRIES)", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper (attempt ${runAttemptCount + 1}/$MAX_RETRIES)", e)
            CrashReporter.recordException(e)
            Result.retry()
        }
    }
}
