package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cascadiacollections.bauhaus.BauhausApplication
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One-shot event for [SnackbarHost][androidx.compose.material3.SnackbarHost] display. */
data class SnackbarEvent(val message: String, val uri: Uri? = null)

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
 * reads the [Application] from [CreationExtras][androidx.lifecycle.viewmodel.CreationExtras].
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
    application: Application,
    private val settings: SettingsRepository,
    private val api: BauhausApi,
) : AndroidViewModel(application) {

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
            } catch (_: Exception) {
                // Metadata is optional — don't block UI if the CDN is unreachable
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
        val app = getApplication<BauhausApplication>()
        viewModelScope.launch {
            settings.setSchedulingEnabled(enabled)
            if (enabled) app.scheduleWallpaperWorker() else app.cancelWallpaperWorker()
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
                val metrics = getApplication<Application>().resources.displayMetrics
                val bitmap = api.fetchTodayImage(
                    maxWidth = metrics.widthPixels,
                    maxHeight = metrics.heightPixels,
                )
                try {
                    val target = _uiState.value.wallpaperTarget
                    val wallpaperManager = WallpaperManager.getInstance(getApplication())
                    wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                    settings.setLastUpdated(LocalDate.now().toString())
                    _uiState.update { it.copy(isSettingWallpaper = false) }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSettingWallpaper = false) }
                _snackbarEvent.tryEmit(SnackbarEvent(e.message ?: "Failed to set wallpaper"))
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

                val resolver = getApplication<Application>().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: throw IllegalStateException("MediaStore insert returned null")

                resolver.openOutputStream(uri)!!.use { it.write(bytes) }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                _uiState.update { it.copy(isSavingImage = false) }
                _snackbarEvent.tryEmit(SnackbarEvent("Image saved", uri))
            } catch (e: Exception) {
                _uiState.update { it.copy(isSavingImage = false) }
                _snackbarEvent.tryEmit(SnackbarEvent(e.message ?: "Failed to save image"))
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
                _snackbarEvent.tryEmit(SnackbarEvent(e.message ?: "Failed to refresh"))
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                BauhausViewModel(
                    app,
                    SettingsRepository(app),
                    BauhausApi(HttpModule.client(app)),
                )
            }
        }
    }
}
