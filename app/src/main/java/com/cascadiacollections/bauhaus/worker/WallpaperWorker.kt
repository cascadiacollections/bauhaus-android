package com.cascadiacollections.bauhaus.worker

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        val api = BauhausApi(HttpModule.client(applicationContext))
        val settings = SettingsRepository(applicationContext)

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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper (attempt ${runAttemptCount + 1}/$MAX_RETRIES)", e)
            Result.retry()
        }
    }
}
