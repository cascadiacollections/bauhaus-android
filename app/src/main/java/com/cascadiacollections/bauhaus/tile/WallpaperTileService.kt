package com.cascadiacollections.bauhaus.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import com.cascadiacollections.bauhaus.AppContainerProvider
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.serviceToday
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Quick Settings tile that updates the wallpaper without opening the app.
 *
 * The tile is a thin trigger, not a second implementation: tapping it enqueues
 * the same [WallpaperWorker][com.cascadiacollections.bauhaus.worker.WallpaperWorker]
 * the daily schedule uses, through
 * [WallpaperScheduler.requestImmediateUpdate][com.cascadiacollections.bauhaus.WallpaperScheduler.requestImmediateUpdate].
 * Every cost guard therefore still applies — the worker skips the fetch entirely
 * when today's artwork is already set, repeated taps collapse into one unique
 * work item, and the retry ceiling is unchanged.
 *
 * Tile state is read from the same `lastUpdated` stamp the worker writes and
 * compared against [serviceToday] rather than `LocalDate.now()`, so the tile
 * agrees with the UTC day the service keys artwork by. Anything else would show
 * "Up to date" on a device east of UTC for a day the service has not published.
 */
class WallpaperTileService : TileService() {

    private companion object {
        const val TAG = "WallpaperTileService"
    }

    // Main.immediate: qsTile and updateTile() are main-thread only, and the
    // settings read that feeds them is a suspending DataStore call.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val lastUpdated = runCatching {
                (application as AppContainerProvider).container
                    .settingsRepository.lastUpdated.first()
            }.getOrElse { e ->
                AppLogger.warn(
                    TAG,
                    AppLogger.Event("tile_state_read_failure"),
                    "Could not read lastUpdated for tile: ${e.message}",
                )
                null
            }
            applyState(tileStateFor(lastUpdated, serviceToday()))
        }
    }

    override fun onClick() {
        super.onClick()
        // Deliberately no unlockAndRun: setting a wallpaper does not need an
        // unlocked device, and demanding a PIN to start a background job is
        // a worse experience than the tile is worth.
        (application as AppContainerProvider).container
            .wallpaperScheduler.requestImmediateUpdate()
        AppLogger.info(TAG, AppLogger.Event("tile_click"), "Immediate wallpaper update requested")
        applyState(TileState(active = true, subtitleRes = R.string.tile_subtitle_updating))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun applyState(state: TileState) {
        // qsTile is null until the system binds the tile, and again once it stops
        // listening — a coroutine resuming then must not crash the service.
        val tile = qsTile ?: return
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(state.subtitleRes)
        tile.contentDescription = getString(R.string.tile_content_description, tile.subtitle)
        tile.updateTile()
    }
}

/** Presentation state of the Quick Settings tile. */
internal data class TileState(
    val active: Boolean,
    @param:StringRes val subtitleRes: Int,
)

/**
 * Maps the persisted `lastUpdated` stamp onto tile state.
 *
 * @param lastUpdated ISO date of the last successful wallpaper set, or `null`.
 * @param today The service's current UTC day, from [serviceToday].
 */
internal fun tileStateFor(lastUpdated: String?, today: LocalDate): TileState =
    if (lastUpdated != null && lastUpdated == today.toString()) {
        TileState(active = true, subtitleRes = R.string.tile_subtitle_up_to_date)
    } else {
        TileState(active = false, subtitleRes = R.string.tile_subtitle_tap_to_update)
    }
