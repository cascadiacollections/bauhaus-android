package com.cascadiacollections.bauhaus

import android.app.Application
import org.robolectric.RuntimeEnvironment
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.cascadiacollections.bauhaus.worker.WallpaperWorker
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WorkManagerWallpaperSchedulerTest {

    private lateinit var context: Application
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerWallpaperScheduler

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerWallpaperScheduler(context)
    }

    @Test
    fun `immediate update enqueues one work item`() {
        scheduler.requestImmediateUpdate()

        assertEquals(1, immediateWork().size)
    }

    @Test
    fun `repeated immediate requests collapse into a single pending run`() {
        // Every extra run is a service request the maintainer pays for. A user
        // hammering the Quick Settings tile must not queue five fetches.
        repeat(5) { scheduler.requestImmediateUpdate() }

        assertEquals(1, immediateWork().size)
    }

    @Test
    fun `immediate update does not disturb the daily schedule`() {
        scheduler.scheduleDaily()

        scheduler.requestImmediateUpdate()

        val periodic = workManager
            .getWorkInfosForUniqueWork(WallpaperWorker.WORK_NAME)
            .get()
            .filterNot { it.state == WorkInfo.State.CANCELLED }
        assertEquals(1, periodic.size)
    }

    private fun immediateWork(): List<WorkInfo> = workManager
        .getWorkInfosForUniqueWork(WallpaperWorker.IMMEDIATE_WORK_NAME)
        .get()
        .filterNot { it.state.isFinished }
}
