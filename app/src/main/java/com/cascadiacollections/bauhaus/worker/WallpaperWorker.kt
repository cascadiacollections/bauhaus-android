package com.cascadiacollections.bauhaus.worker

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class WallpaperWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "WallpaperWorker"
        const val WORK_NAME = "daily_wallpaper"
    }

    override suspend fun doWork(): Result {
        val api = BauhausApi()
        val settings = SettingsRepository(applicationContext)

        return try {
            val bitmap = api.fetchTodayImage()
            val target = settings.wallpaperTarget.first()
            val wallpaperManager = WallpaperManager.getInstance(applicationContext)
            wallpaperManager.setBitmap(bitmap, null, true, target.flag)
            settings.setLastUpdated(LocalDate.now().toString())
            Log.i(TAG, "Wallpaper set for target: ${target.name}")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set wallpaper", e)
            Result.retry()
        }
    }
}
