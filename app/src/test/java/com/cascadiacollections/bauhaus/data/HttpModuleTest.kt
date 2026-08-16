package com.cascadiacollections.bauhaus.data

import android.app.Application
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HttpModuleTest {

    @Test
    fun `create returns the same instance on repeated calls`() {
        // OkHttp's disk Cache requires exactly one live instance per directory.
        // A second OkHttpClient built over the same http_cache directory can
        // corrupt the cache journal and defeat the Vary: Accept cache-key
        // sharing between BauhausApi, Coil, and the worker.
        val context = RuntimeEnvironment.getApplication()

        val first = HttpModule.create(context)
        val second = HttpModule.create(context)

        assertSame(first, second)
    }
}
