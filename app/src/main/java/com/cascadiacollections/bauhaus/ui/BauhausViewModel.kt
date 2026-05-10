package com.cascadiacollections.bauhaus.ui

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cascadiacollections.bauhaus.AppLogger
import com.cascadiacollections.bauhaus.BauhausApplication
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.WallpaperScheduler
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausDataException
import com.cascadiacollections.bauhaus.data.BauhausDecodeException
import com.cascadiacollections.bauhaus.data.BauhausEmptyBodyException
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.BauhausNetworkException
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One-shot event for [SnackbarHost][androidx.compose.material3.SnackbarHost] display. */
data class SnackbarEvent(
    val message: String,
    val uri: Uri? = null,
    val actionLabel: String? = null,
)

/**
 * Immutable snapshot of the settings screen.
 *
 * Every field drives a corresponding UI element in [SettingsScreen]; Compose
 * recomposes only the affected subtree when a single field changes.
 */
data class UiState(
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.BOTH,
    val schedulingEnabled: Boolean = true,
    val lastUpdated: String? = null,
    val metadata: com.cascadiacollections.bauhaus.data.ArtworkMetadata? = null,
    val isSettingWallpaper: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSavingImage: Boolean = false,
    val imageRevision: Int = 0,
)

/**
 * Drives the [SettingsScreen] UI by combining [SettingsRepository] flows with
 * transient action state (loading spinners, snackbar events).
 *
 * ## Dependency injection
 *
 * [settings] and [api] are constructor parameters so the ViewModel can be
 * tested with fakes. Production construction goes through [Factory], which
 * reads the application instance from
 * [CreationExtras][androidx.lifecycle.viewmodel.CreationExtras].
 *
 * ## COGs Note
 *
 * Metadata is fetched once per ViewModel lifecycle (i.e. once per activity
 * creation). The CDN caches `/api/today.json` for 5 min and the OkHttp disk
 * cache respects that header, so rapid config-change rotations cost nothing.
 *
 * The "Set Now" action fetches the image bytes through the same cached
 * [OkHttpClient][okhttp3.OkHttpClient], so if the Coil preview already loaded
 * the image it may already be in the HTTP cache.
 */
class BauhausViewModel(
    private val appContext: Context,
    private val settings: SettingsStore,
    private val api: BauhausApiClient,
    private val wallpaperScheduler: WallpaperScheduler,
) : ViewModel() {

    /** Minimum milliseconds between user-initiated refreshes (DOS guard). */
    private val refreshCooldownMs: Long = 30_000L
    private var lastRefreshAt: Long = 0L

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            settings.wallpaperTarget.collect { target ->
                _uiState.update { it.copy(wallpaperTarget = target) }
            }
        }
        viewModelScope.launch {
            settings.schedulingEnabled.collect { enabled ->
                _uiState.update { it.copy(schedulingEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settings.lastUpdated.collect { date ->
                _uiState.update { it.copy(lastUpdated = date) }
            }
        }
        viewModelScope.launch {
            try {
                val metadata = api.fetchTodayMetadata()
                _uiState.update { it.copy(metadata = metadata) }
            } catch (e: Exception) {
                // Metadata is optional — don't block UI if the CDN is unreachable
                AppLogger.warn(
                    "BauhausViewModel",
                    AppLogger.Event("metadata_initial_failure"),
                    "Initial metadata fetch failed: ${e.toUserMessage(appContext)}",
                )
            }
        }
    }

    /** Persists the selected wallpaper target (home, lock, or both). */
    fun setWallpaperTarget(target: WallpaperTarget) {
        viewModelScope.launch {
            settings.setWallpaperTarget(target)
        }
    }

    /**
     * Toggles the daily scheduling worker on or off.
     *
     * When disabled, the existing periodic [WorkManager][androidx.work.WorkManager]
     * job is cancelled. Re-enabling re-enqueues it with [ExistingPeriodicWorkPolicy.KEEP][androidx.work.ExistingPeriodicWorkPolicy.KEEP].
     */
    fun setSchedulingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setSchedulingEnabled(enabled)
            if (enabled) wallpaperScheduler.scheduleDaily() else wallpaperScheduler.cancelDaily()
        }
    }

    /**
     * Immediately fetches today's artwork and applies it as the wallpaper.
     *
     * The bitmap is downsampled to the device screen resolution and recycled
     * after [WallpaperManager.setBitmap] to minimize native memory usage.
     */
    fun setWallpaperNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSettingWallpaper = true) }
            try {
                val metrics = appContext.resources.displayMetrics
                val bitmap = api.fetchTodayImage(
                    maxWidth = metrics.widthPixels,
                    maxHeight = metrics.heightPixels,
                )
                try {
                    val target = _uiState.value.wallpaperTarget
                    withContext(Dispatchers.IO) {
                        val wallpaperManager = WallpaperManager.getInstance(appContext)
                        wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                    }
                    settings.setLastUpdated(LocalDate.now().toString())
                    _uiState.update { it.copy(isSettingWallpaper = false) }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSettingWallpaper = false) }
                AppLogger.error(
                    "BauhausViewModel",
                    AppLogger.Event("set_wallpaper_failure", mapOf("target" to _uiState.value.wallpaperTarget.name)),
                    "Set wallpaper failed",
                    e,
                )
                _snackbarEvent.tryEmit(SnackbarEvent(e.toUserMessage(appContext)))
            }
        }
    }

    /**
     * Saves today's artwork to the device gallery in its original format.
     *
     * Uses [MediaStore] to write into `Pictures/Bauhaus/` without requiring
     * storage permissions (minSdk 35). The `IS_PENDING` flag prevents the
     * media scanner from indexing a partially-written file.
     */
    fun saveImageToGallery() {
        if (_uiState.value.isSavingImage) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingImage = true) }
            try {
                val uri = withContext(Dispatchers.IO) {
                    var pendingUri: Uri? = null
                    val (bytes, mimeType) = api.fetchTodayImageRaw()
                    val extension = when (mimeType) {
                        "image/avif" -> "avif"
                        "image/webp" -> "webp"
                        else -> "jpg"
                    }
                    val displayName = "bauhaus_${LocalDate.now()}.$extension"

                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Bauhaus")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val resolver = appContext.contentResolver
                    pendingUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    val uri = pendingUri
                        ?: throw IllegalStateException("MediaStore insert returned null")
                    try {
                        resolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("Failed to open output stream")

                        contentValues.clear()
                        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)
                        uri
                    } catch (writeError: Exception) {
                        resolver.delete(uri, null, null)
                        throw writeError
                    }
                }

                _uiState.update { it.copy(isSavingImage = false) }
                _snackbarEvent.tryEmit(
                    SnackbarEvent(
                        message = appContext.getString(R.string.image_saved),
                        uri = uri,
                        actionLabel = appContext.getString(R.string.open),
                    ),
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isSavingImage = false) }
                AppLogger.error(
                    "BauhausViewModel",
                    AppLogger.Event("save_image_failure"),
                    "Save image failed",
                    e,
                )
                _snackbarEvent.tryEmit(SnackbarEvent(e.toUserMessage(appContext)))
            }
        }
    }

    /**
     * Refreshes today's artwork metadata via a pull-to-refresh gesture.
     *
     * Includes two abuse/DOS guards:
     * 1. **In-flight guard**: drops the call immediately if a refresh is already
     *    in progress, preventing concurrent network requests.
     * 2. **Cooldown guard**: successive calls within [refreshCooldownMs] are
     *    silently dropped to prevent hammering the upstream Bauhaus service.
     *    Uses [SystemClock.elapsedRealtime] (monotonic) so the check is immune
     *    to wall-clock adjustments (NTP, manual time changes).
     */
    fun refresh() {
        if (_uiState.value.isRefreshing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRefreshAt < refreshCooldownMs) return
        lastRefreshAt = now
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val metadata = api.fetchTodayMetadata()
                _uiState.update { it.copy(metadata = metadata, isRefreshing = false, imageRevision = it.imageRevision + 1) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false) }
                AppLogger.error(
                    "BauhausViewModel",
                    AppLogger.Event("metadata_refresh_failure"),
                    "Refresh failed",
                    e,
                )
                _snackbarEvent.tryEmit(SnackbarEvent(e.toUserMessage(appContext)))
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                val bauhausApp = app as BauhausApplication
                BauhausViewModel(
                    app.applicationContext,
                    bauhausApp.container.settingsRepository,
                    bauhausApp.container.bauhausApi,
                    bauhausApp.container.wallpaperScheduler,
                )
            }
        }
    }
}

private fun Throwable.toUserMessage(context: Context): String {
    return when (this) {
        is BauhausNetworkException -> context.getString(R.string.error_network_unavailable)
        is BauhausHttpException -> {
            if (code in 500..599) {
                context.getString(R.string.error_service_unavailable)
            } else {
                context.getString(R.string.error_request_failed_with_code, code)
            }
        }

        is BauhausDecodeException, is BauhausEmptyBodyException -> context.getString(R.string.error_invalid_response)
        is BauhausDataException -> context.getString(R.string.error_request_generic)
        else -> context.getString(R.string.error_generic)
    }
}
