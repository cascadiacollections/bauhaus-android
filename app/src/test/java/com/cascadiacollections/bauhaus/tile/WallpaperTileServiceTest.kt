package com.cascadiacollections.bauhaus.tile

import android.app.Application
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.serviceToday
import java.time.LocalDate
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WallpaperTileServiceTest {

    private val today = LocalDate.of(2026, 8, 4)

    @Test
    fun `tile is active when the wallpaper was already set for the service day`() {
        val state = tileStateFor(lastUpdated = "2026-08-04", today = today)

        assertThat(state.active).isTrue()
        assertThat(state.subtitleRes).isEqualTo(R.string.tile_subtitle_up_to_date)
    }

    @Test
    fun `tile is inactive when the wallpaper has never been set`() {
        val state = tileStateFor(lastUpdated = null, today = today)

        assertThat(state.active).isFalse()
        assertThat(state.subtitleRes).isEqualTo(R.string.tile_subtitle_tap_to_update)
    }

    @Test
    fun `tile is inactive when the stamp is from an earlier day`() {
        val state = tileStateFor(lastUpdated = "2026-08-03", today = today)

        assertThat(state.active).isFalse()
        assertThat(state.subtitleRes).isEqualTo(R.string.tile_subtitle_tap_to_update)
    }

    @Test
    fun `tile is inactive when the stamp names a day the service is not on yet`() {
        // A device east of UTC can have stamped a date the service has not
        // published. Showing "Up to date" there would hide the real state and
        // discourage the tap that would fix it.
        val state = tileStateFor(lastUpdated = "2026-08-05", today = today)

        assertThat(state.active).isFalse()
        assertThat(state.subtitleRes).isEqualTo(R.string.tile_subtitle_tap_to_update)
    }

    @Test
    fun `tile state is keyed to the service UTC day`() {
        // Guards against a future refactor swapping serviceToday() for
        // LocalDate.now(), which would disagree with the worker's own guard.
        val state = tileStateFor(lastUpdated = serviceToday().toString(), today = serviceToday())

        assertThat(state.active).isTrue()
    }
}
