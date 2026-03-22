package com.cascadiacollections.bauhaus

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import kotlin.time.Duration.Companion.hours
import kotlin.time.toJavaDuration

class BauhausApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleWallpaperWorker()
    }

    fun scheduleWallpaperWorker() {
        val constraints = Constraints(
            requiredNetworkType = NetworkType.CONNECTED,
        )

        val workRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(
            repeatInterval = 24.hours.toJavaDuration(),
            flexTimeInterval = 1.hours.toJavaDuration(),
        )
            .setConstraints(constraints)
            .addTag(WallpaperWorker.TAG)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WallpaperWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest,
        )
    }

    fun cancelWallpaperWorker() {
        WorkManager.getInstance(this).cancelUniqueWork(WallpaperWorker.WORK_NAME)
    }
}
