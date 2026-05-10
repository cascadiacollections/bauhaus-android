package com.cascadiacollections.bauhaus.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

class BauhausWorkerFactory(
    private val dependencies: WallpaperWorker.Dependencies,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            WallpaperWorker::class.java.name -> WallpaperWorker(
                context = appContext,
                params = workerParameters,
                dependencies = dependencies,
            )

            else -> null
        }
    }
}
