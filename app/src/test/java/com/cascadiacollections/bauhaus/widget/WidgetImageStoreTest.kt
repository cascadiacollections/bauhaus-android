package com.cascadiacollections.bauhaus.widget

import android.app.Application
import android.graphics.Bitmap
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WidgetImageStoreTest {

    private val context: Application = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        context.cacheDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun `read returns null before anything is written`() = runTest {
        assertThat(WidgetImageStore.read(context)).isNull()
    }

    @Test
    fun `a written bitmap can be read back`() = runTest {
        WidgetImageStore.write(context, bitmap(64, 64))

        assertThat(WidgetImageStore.read(context)).isNotNull()
    }

    @Test
    fun `an oversized bitmap is downsampled before storage`() = runTest {
        // A widget bitmap crosses into the launcher process, which caps its
        // size. Storing a full-resolution wallpaper would exceed that cap.
        WidgetImageStore.write(context, bitmap(4096, 2048))

        val stored = WidgetImageStore.read(context)!!
        assertThat(stored.width).isEqualTo(1024)
        assertThat(stored.height).isEqualTo(512)
    }

    @Test
    fun `a bitmap already within bounds keeps its dimensions`() = runTest {
        WidgetImageStore.write(context, bitmap(800, 600))

        val stored = WidgetImageStore.read(context)!!
        assertThat(stored.width).isEqualTo(800)
        assertThat(stored.height).isEqualTo(600)
    }

    @Test
    fun `writing does not recycle the caller's bitmap`() = runTest {
        // Both writers recycle the bitmap themselves once done with it; a
        // double recycle here would take down the wallpaper update.
        val source = bitmap(2048, 2048)

        WidgetImageStore.write(context, source)

        assertThat(source.isRecycled).isFalse()
    }

    @Test
    fun `a second write replaces the first`() = runTest {
        WidgetImageStore.write(context, bitmap(800, 600))
        WidgetImageStore.write(context, bitmap(400, 400))

        val stored = WidgetImageStore.read(context)!!
        assertThat(stored.width).isEqualTo(400)
    }

    @Test
    fun `no temporary file survives a write`() = runTest {
        WidgetImageStore.write(context, bitmap(64, 64))

        val leftovers = context.cacheDir.listFiles().orEmpty().filter(File::isFile)
        assertThat(
            leftovers.none { it.name.endsWith(".tmp") },
            name = "unexpected leftovers: ${leftovers.map(File::getName)}"
        ).isTrue()
    }

    private fun bitmap(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
}
