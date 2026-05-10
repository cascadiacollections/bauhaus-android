package com.cascadiacollections.bauhaus

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

interface WallpaperScheduler {
    fun scheduleDaily()
    fun cancelDaily()
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
}
