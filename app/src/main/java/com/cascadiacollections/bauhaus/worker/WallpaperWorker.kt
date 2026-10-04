package com.cascadiacollections.bauhaus.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausDecodeException
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.SystemWallpaperSetter
import com.cascadiacollections.bauhaus.data.WallpaperSetter
import com.cascadiacollections.bauhaus.data.isConnectivityFailure
import com.cascadiacollections.bauhaus.data.serviceToday
import com.cascadiacollections.bauhaus.data.wallpaperTargetSize
import com.cascadiacollections.bauhaus.notification.WallpaperNotifier
import com.cascadiacollections.bauhaus.widget.BauhausAppWidget
import com.cascadiacollections.bauhaus.widget.WidgetImageStore
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Background worker that fetches the newest published bauhaus artwork and
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
 * If `lastUpdated` already matches today's UTC date, the worker short-circuits
 * with [Result.success] — no request at all. This handles the case where the
 * user manually tapped "Set Now" earlier in the day, or the worker runs twice
 * within the flex window.
 *
 * Otherwise it asks `/api/today.json` which day the service is on. When that is
 * the day already applied — the run came before the day's publish — it stops
 * there, having spent one small cached request rather than an image download.
 *
 * ## Which day gets stamped
 *
 * `lastUpdated` records the day the applied artwork was **published for**, not
 * the day the worker ran. Before the day's publish, `/api/today` is still
 * yesterday's artwork; stamping the clock's date then made every later run that
 * day — including the one after publishing — read as "already done", and the
 * day's artwork was skipped entirely.
 *
 * ## Retry Policy
 *
 * A connectivity failure, or any other transient fault, returns [Result.retry]
 * with exponential backoff (WorkManager default). After [MAX_RETRIES]
 * consecutive failures it returns [Result.failure] to avoid hammering the CDN —
 * this is important for COGs because the CDN owner (you) pays per-request.
 *
 * A failure no retry can fix — a 4xx from the service, or a response that does
 * not decode — returns [Result.failure] straight away instead of spending the
 * remaining attempts on the same answer.
 *
 * ## Memory
 *
 * The fetched [Bitmap][android.graphics.Bitmap] is downsampled to the device's screen resolution and
 * explicitly recycled after it is applied to free native heap immediately.
 */
class WallpaperWorker(context: Context, params: WorkerParameters, private val dependencies: Dependencies) :
    CoroutineWorker(context, params) {

    companion object {
        const val TAG = "WallpaperWorker"
        const val WORK_NAME = "daily_wallpaper"

        /**
         * Unique name for user-initiated one-shot runs (Quick Settings tile,
         * first launch). Kept distinct from [WORK_NAME] so an immediate run
         * never replaces or cancels the periodic schedule.
         */
        const val IMMEDIATE_WORK_NAME = "immediate_wallpaper"

        /**
         * Input-data flag marking a run the user asked for right now, as opposed
         * to the daily schedule. Only these runs notify.
         */
        const val KEY_USER_INITIATED = "user_initiated"
        private const val MAX_RETRIES = 3
        private val CLIENT_ERRORS = 400..499
    }

    data class Dependencies(
        val settings: SettingsStore,
        val api: BauhausApiClient,
        val wallpaperSetter: WallpaperSetter = SystemWallpaperSetter
    )

    override suspend fun doWork(): Result {
        // Only a run the user just asked for gets to interrupt them with a
        // notification. The daily schedule stays silent.
        val userInitiated = inputData.getBoolean(KEY_USER_INITIATED, false)

        if (runAttemptCount >= MAX_RETRIES) {
            AppLogger.warn(
                TAG,
                AppLogger.Event("worker_give_up", mapOf("attempt" to "$runAttemptCount")),
                "Giving up after $MAX_RETRIES attempts to avoid excessive CDN requests"
            )
            if (userInitiated) WallpaperNotifier.showFailed(applicationContext)
            return Result.failure()
        }

        val settings = dependencies.settings

        // Skip if we already set today's wallpaper (e.g. user tapped "Set Now",
        // or the worker ran twice within the flex window). Saves every request.
        //
        // serviceToday() rather than LocalDate.now() so this matches the UTC day
        // the service keys artwork by — the same calendar the stamp below and the
        // ViewModel's "Set Now" stamp use.
        val today = serviceToday()
        val lastUpdated = settings.lastUpdated.first()
        if (lastUpdated == today.toString()) {
            AppLogger.info(
                TAG,
                AppLogger.Event("worker_skip_today", mapOf("date" to "$today")),
                "Wallpaper already set for $today, skipping CDN fetch"
            )
            return Result.success()
        }

        val api = dependencies.api

        // Posted before the fetch, not after: the whole point is to show that
        // something is happening during the slow part.
        if (userInitiated) WallpaperNotifier.showUpdating(applicationContext)

        return try {
            // The day /api/today currently resolves to. Before the day's publish it
            // is yesterday, and that — not the clock — is what gets stamped.
            val publishedDate = api.fetchTodayMetadata().publishedDate
            if (publishedDate != null && lastUpdated == publishedDate.toString()) {
                AppLogger.info(
                    TAG,
                    AppLogger.Event("worker_skip_unpublished", mapOf("date" to "$publishedDate")),
                    "Newest artwork ($publishedDate) is already set; today's is not published yet"
                )
                if (userInitiated) WallpaperNotifier.clear(applicationContext)
                return Result.success()
            }

            applyNewest(publishedDate)
            if (userInitiated) WallpaperNotifier.clear(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager stopped us. Not a failure, and reporting it as one would
            // both retry pointlessly and file a non-bug with the crash reporter.
            if (userInitiated) WallpaperNotifier.clear(applicationContext)
            throw e
        } catch (e: Exception) {
            onFailure(e, userInitiated)
        }
    }

    /**
     * Fetches, applies, and stamps the artwork published for [publishedDate], or
     * `/api/today`'s when the service named no date.
     */
    private suspend fun applyNewest(publishedDate: LocalDate?) {
        val api = dependencies.api
        val settings = dependencies.settings
        val targetSize = wallpaperTargetSize(applicationContext)
        // The date-keyed route, so the image is exactly the day being stamped:
        // `/api/today` and `/api/today.json` are cached independently and can
        // briefly disagree around a publish.
        val bitmap = if (publishedDate != null) {
            api.fetchImageForDate(publishedDate, maxWidth = targetSize.width, maxHeight = targetSize.height)
        } else {
            api.fetchTodayImage(maxWidth = targetSize.width, maxHeight = targetSize.height)
        }

        try {
            val target = settings.wallpaperTarget.first()
            dependencies.wallpaperSetter.set(applicationContext, bitmap, target)
            // With no publish date to go on, stamp nothing: a wrong stamp
            // suppresses a whole day, a missing one costs one more fetch.
            publishedDate?.let { settings.setLastUpdated(it.toString()) }
            AppLogger.info(
                TAG,
                AppLogger.Event("worker_set_success", mapOf("target" to target.name, "date" to "$publishedDate")),
                "Wallpaper set for target: ${target.name}"
            )
            // Feeds the widget from the bitmap already in hand. The widget
            // never fetches for itself, so this is its only source.
            WidgetImageStore.write(applicationContext, bitmap)
            BauhausAppWidget.refresh(applicationContext)
        } finally {
            bitmap.recycle()
        }
    }

    private fun onFailure(e: Exception, userInitiated: Boolean): Result {
        val permanent = e.isPermanentFailure
        val event = AppLogger.Event(
            "worker_set_failure",
            mapOf(
                "attempt" to "${runAttemptCount + 1}",
                "maxRetries" to "$MAX_RETRIES",
                "permanent" to "$permanent"
            )
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
        if (!permanent) return Result.retry()
        if (userInitiated) WallpaperNotifier.showFailed(applicationContext)
        return Result.failure()
    }

    /**
     * `true` for a failure that retrying cannot change: the service answered,
     * and the answer was a client error or something that does not decode.
     * Connectivity failures are never permanent — they are the case retries are for.
     */
    private val Throwable.isPermanentFailure: Boolean
        get() = !isConnectivityFailure &&
            ((this is BauhausHttpException && code in CLIENT_ERRORS) || this is BauhausDecodeException)
}
