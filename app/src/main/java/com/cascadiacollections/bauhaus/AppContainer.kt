package com.cascadiacollections.bauhaus

import android.app.Application
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.worker.BauhausWorkerFactory
import com.cascadiacollections.bauhaus.worker.WallpaperWorker

class AppContainer(app: Application) {
    val settingsRepository: SettingsStore = SettingsRepository(app)
    val bauhausApi: BauhausApiClient = BauhausApi(HttpModule.client(app))
    val wallpaperScheduler: WallpaperScheduler = WorkManagerWallpaperScheduler(app)
    val workerFactory = BauhausWorkerFactory(
        WallpaperWorker.Dependencies(
            settings = settingsRepository,
            api = bauhausApi,
        ),
    )
}
