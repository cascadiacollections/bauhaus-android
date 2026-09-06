package com.cascadiacollections.bauhaus.worker

import android.app.WallpaperManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.isConnectivityFailure
import com.cascadiacollections.bauhaus.data.serviceToday
import com.cascadiacollections.bauhaus.data.wallpaperTargetSize
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

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
    private val dependencies: Dependencies,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "WallpaperWorker"
        const val WORK_NAME = "daily_wallpaper"

        /**
         * Unique name for user-initiated one-shot runs (Quick Settings tile,
         * first launch). Kept distinct from [WORK_NAME] so an immediate run
         * never replaces or cancels the periodic schedule.
         */
        const val IMMEDIATE_WORK_NAME = "immediate_wallpaper"
        private const val MAX_RETRIES = 3
    }

    data class Dependencies(
        val settings: SettingsStore,
        val api: BauhausApiClient,
    )

    override suspend fun doWork(): Result {
        if (runAttemptCount >= MAX_RETRIES) {
            AppLogger.warn(
                TAG,
                AppLogger.Event("worker_give_up", mapOf("attempt" to "$runAttemptCount")),
                "Giving up after $MAX_RETRIES attempts to avoid excessive CDN requests",
            )
            return Result.failure()
        }

        val settings = dependencies.settings

        // Skip if we already set today's wallpaper (e.g. user tapped "Set Now",
        // or the worker ran twice within the flex window). Saves a CDN request.
        //
        // Read the date once and reuse it: the guard and the stamp below must
        // agree, and two separate clock reads can straddle midnight. serviceToday()
        // rather than LocalDate.now() so this matches the UTC day the service keys
        // artwork by — and the day the ViewModel stamps after "Set Now".
        val today = serviceToday().toString()
        val lastUpdated = settings.lastUpdated.first()
        if (lastUpdated == today) {
            AppLogger.info(
                TAG,
                AppLogger.Event("worker_skip_today", mapOf("date" to today)),
                "Wallpaper already set for $today, skipping CDN fetch",
            )
            return Result.success()
        }

        val api = dependencies.api

        return try {
            val targetSize = wallpaperTargetSize(applicationContext)
            val bitmap = api.fetchTodayImage(
                maxWidth = targetSize.width,
                maxHeight = targetSize.height,
            )

            try {
                val target = settings.wallpaperTarget.first()
                val wallpaperManager = WallpaperManager.getInstance(applicationContext)
                wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                settings.setLastUpdated(today)
                AppLogger.info(
                    TAG,
                    AppLogger.Event("worker_set_success", mapOf("target" to target.name)),
                    "Wallpaper set for target: ${target.name}",
                )
                Result.success()
            } finally {
                bitmap.recycle()
            }
        } catch (e: CancellationException) {
            // WorkManager stopped us. Not a failure, and reporting it as one would
            // both retry pointlessly and file a non-bug with the crash reporter.
            throw e
        } catch (e: Exception) {
            val event = AppLogger.Event(
                "worker_set_failure",
                mapOf("attempt" to "${runAttemptCount + 1}", "maxRetries" to "$MAX_RETRIES"),
            )
            val message = "Failed to set wallpaper (attempt ${runAttemptCount + 1}/$MAX_RETRIES)"
            // A background job finding the device offline is routine. Logging it as
            // an error records an exception per retry per device, which buries real
            // faults in connectivity noise.
            if (e.isConnectivityFailure) {
                AppLogger.warn(TAG, event, "$message: ${e.message}")
            } else {
                AppLogger.error(TAG, event, message, e)
            }
            Result.retry()
        }
    }
}
