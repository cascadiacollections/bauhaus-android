package com.cascadiacollections.bauhaus

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

interface WallpaperScheduler {
    fun scheduleDaily()
    fun cancelDaily()

    /**
     * Runs the wallpaper update once, as soon as the network allows.
     *
     * Used by entry points that have no UI to report progress into — the
     * Quick Settings tile and the first-run path. Enqueued as *unique* work with
     * [ExistingWorkPolicy.KEEP] so repeated taps collapse into the single run
     * that is already pending, and the worker's own "already set today" guard
     * means a tap on a day that is already done costs no service request at all.
     */
    fun requestImmediateUpdate()
}

class WorkManagerWallpaperScheduler(
    private val context: Context,
) : WallpaperScheduler {
    override fun scheduleDaily() {
        val constraints = Constraints(requiredNetworkType = NetworkType.CONNECTED)
        val workRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(
            repeatInterval = 24.hours.toJavaDuration(),
            flexTimeInterval = 1.hours.toJavaDuration(),
        )
            .setConstraints(constraints)
            .addTag(WallpaperWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WallpaperWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    override fun cancelDaily() {
        WorkManager.getInstance(context).cancelUniqueWork(WallpaperWorker.WORK_NAME)
    }

    override fun requestImmediateUpdate() {
        val workRequest = OneTimeWorkRequestBuilder<WallpaperWorker>()
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf(WallpaperWorker.KEY_USER_INITIATED to true))
            .addTag(WallpaperWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WallpaperWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest,
        )
    }
}
