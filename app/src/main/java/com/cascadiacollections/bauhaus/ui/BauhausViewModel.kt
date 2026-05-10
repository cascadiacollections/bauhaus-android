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
import com.cascadiacollections.bauhaus.CrashReporter
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.HttpModule
import com.cascadiacollections.bauhaus.data.SettingsRepository
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
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One-shot event for [SnackbarHost][androidx.compose.material3.SnackbarHost] display. */
data class SnackbarEvent(val message: String, val uri: Uri? = null)
data class ShareArtworkEvent(val uri: Uri, val text: String)

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
    val visibleDate: LocalDate = LocalDate.now(),
    val availableDates: List<LocalDate> = listOf(LocalDate.now()),
    val reachedArchiveStart: Boolean = false,
    val metadata: ArtworkMetadata? = null,
    val isMetadataLoading: Boolean = true,
    val metadataLoadFailed: Boolean = false,
    val isSettingWallpaper: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSavingImage: Boolean = false,
    val imageRevision: Int = 0,
    val isFavorite: Boolean = false,
    val showFavoritesOnly: Boolean = false,
    val favoriteDates: Set<LocalDate> = emptySet(),
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
    private val maxJumpExpansionDays: Long = 730
    private val today: LocalDate = LocalDate.now()
    private val metadataByDate = mutableMapOf<LocalDate, ArtworkMetadata>()
    private var isAppendingOlderDate: Boolean = false

    /**
     * Tracks the full chronological list of dates available for browsing
     * (independent of the favorites filter). Restored when the user exits
     * favorites-only mode.
     */
    private var allBrowsableDates: List<LocalDate> = listOf(today)

    /** Minimum milliseconds between user-initiated refreshes (DOS guard). */
    private val refreshCooldownMs: Long = 30_000L
    private var lastRefreshAt: Long = 0L

    private fun getString(@androidx.annotation.StringRes resId: Int): String =
        getApplication<Application>().getString(resId)

    private val _uiState = MutableStateFlow(
        UiState(
            visibleDate = today,
            availableDates = listOf(today),
        ),
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent: SharedFlow<SnackbarEvent> = _snackbarEvent.asSharedFlow()
    private val _shareArtworkEvent = MutableSharedFlow<ShareArtworkEvent>(extraBufferCapacity = 1)
    val shareArtworkEvent: SharedFlow<ShareArtworkEvent> = _shareArtworkEvent.asSharedFlow()

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
            settings.favorites.collect { favStrings ->
                val favDates = favStrings.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
                var metadataDateToLoad: LocalDate? = null
                _uiState.update { state ->
                    val newAvailableDates = if (state.showFavoritesOnly) {
                        favDates.sortedDescending()
                    } else {
                        state.availableDates
                    }
                    val newVisibleDate = if (state.showFavoritesOnly && state.visibleDate !in newAvailableDates) {
                        newAvailableDates.firstOrNull() ?: state.visibleDate
                    } else {
                        state.visibleDate
                    }
                    val cached = metadataByDate[newVisibleDate]
                    if (cached == null) {
                        metadataDateToLoad = newVisibleDate
                    }
                    state.copy(
                        visibleDate = newVisibleDate,
                        metadata = cached,
                        isFavorite = newVisibleDate in favDates,
                        favoriteDates = favDates,
                        availableDates = newAvailableDates,
                    )
                }
                metadataDateToLoad?.let { loadMetadataForDate(it, force = false) }
            }
        }
        viewModelScope.launch {
            try {
                val metadata = api.fetchTodayMetadata()
                metadataByDate[today] = metadata
                _uiState.update { it.copy(metadata = metadata, isMetadataLoading = false, metadataLoadFailed = false) }
            } catch (e: IOException) {
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
                _uiState.update { it.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true) }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                _uiState.update { it.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true) }
            }
        }
    }

    fun onArchivePageSelected(pageIndex: Int) {
        val snapshot = _uiState.value
        val selectedDate = snapshot.availableDates.getOrNull(pageIndex) ?: return

        if (snapshot.visibleDate != selectedDate) {
            val cached = metadataByDate[selectedDate]
            _uiState.update {
                it.copy(
                    visibleDate = selectedDate,
                    metadata = cached,
                    isMetadataLoading = cached == null,
                    metadataLoadFailed = false,
                    isFavorite = selectedDate in it.favoriteDates,
                )
            }
            if (cached == null) {
                loadMetadataForDate(selectedDate, force = false)
            }
        }

        if (!snapshot.showFavoritesOnly && !snapshot.reachedArchiveStart && pageIndex == snapshot.availableDates.lastIndex) {
            appendNextOlderDate()
        }
    }

    fun jumpToDate(date: LocalDate) {
        if (date.isAfter(today)) return
        val snapshot = _uiState.value
        if (date == snapshot.visibleDate) return

        val existingIndex = snapshot.availableDates.indexOf(date)
        if (existingIndex >= 0) {
            val cached = metadataByDate[date]
            _uiState.update { it.copy(visibleDate = date, metadata = cached) }
            if (cached == null) {
                loadMetadataForDate(date, force = false)
            }
            return
        }

        val oldestLoadedDate = snapshot.availableDates.lastOrNull() ?: today
        if (!date.isBefore(oldestLoadedDate)) {
            val cached = metadataByDate[date]
            _uiState.update { it.copy(visibleDate = date, metadata = cached) }
            if (cached == null) {
                loadMetadataForDate(date, force = false)
            }
            return
        }

        val missingSpanDays = ChronoUnit.DAYS.between(date, oldestLoadedDate)
        if (missingSpanDays > maxJumpExpansionDays) {
            _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
            return
        }

        viewModelScope.launch {
            val appendedDates = mutableListOf<LocalDate>()
            var reachedArchiveStart = false
            var cursor = oldestLoadedDate.minusDays(1)
            while (cursor >= date && appendedDates.size < maxJumpExpansionDays) {
                try {
                    val metadata = api.fetchMetadataForDate(cursor)
                    metadataByDate[cursor] = metadata
                    appendedDates += cursor
                    if (cursor == date) break
                } catch (e: BauhausHttpException) {
                    if (e.code == 404) {
                        reachedArchiveStart = true
                        break
                    }
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                    return@launch
                } catch (e: IOException) {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
                    return@launch
                } catch (e: Exception) {
                    CrashReporter.recordException(e)
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                    return@launch
                }
                cursor = cursor.minusDays(1)
            }

            val targetReached = appendedDates.contains(date)
            _uiState.update {
                val mergedDates = if (appendedDates.isNotEmpty()) {
                    it.availableDates + appendedDates
                } else {
                    it.availableDates
                }
                if (targetReached) {
                    it.copy(
                        availableDates = mergedDates,
                        visibleDate = date,
                        metadata = metadataByDate[date],
                        reachedArchiveStart = it.reachedArchiveStart || reachedArchiveStart,
                    )
                } else {
                    it.copy(
                        availableDates = mergedDates,
                        reachedArchiveStart = it.reachedArchiveStart || reachedArchiveStart,
                    )
                }
            }

            if (targetReached) {
                if (metadataByDate[date] == null) {
                    loadMetadataForDate(date, force = false)
                }
            } else {
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
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
     * Toggles the favorite state of the currently visible artwork date.
     *
     * The change is persisted to DataStore and reflected immediately in [uiState]
     * via the [favorites][SettingsStore.favorites] flow.
     */
    fun toggleFavorite() {
        val date = _uiState.value.visibleDate
        viewModelScope.launch {
            settings.toggleFavorite(date.toString())
        }
    }

    /**
     * Switches between the full chronological browsing mode and a
     * favorites-only view that shows only the dates the user has hearted.
     *
     * When entering favorites-only mode the pager is replaced with the
     * sorted favorites list; exiting restores [allBrowsableDates].
     */
    fun toggleFavoritesFilter() {
        var metadataDateToLoad: LocalDate? = null
        _uiState.update { state ->
            val newShowFavoritesOnly = !state.showFavoritesOnly
            val newAvailableDates = if (newShowFavoritesOnly) {
                state.favoriteDates.sortedDescending()
            } else {
                allBrowsableDates
            }
            val newVisibleDate = if (newAvailableDates.contains(state.visibleDate)) {
                state.visibleDate
            } else {
                newAvailableDates.firstOrNull() ?: state.visibleDate
            }
            val cached = metadataByDate[newVisibleDate]
            if (cached == null) {
                metadataDateToLoad = newVisibleDate
            }
            state.copy(
                showFavoritesOnly = newShowFavoritesOnly,
                availableDates = newAvailableDates,
                visibleDate = newVisibleDate,
                metadata = cached,
                isFavorite = newVisibleDate in state.favoriteDates,
            )
        }
        metadataDateToLoad?.let { loadMetadataForDate(it, force = false) }
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
                val visibleDate = _uiState.value.visibleDate
                val metrics = getApplication<Application>().resources.displayMetrics
                val bitmap = if (visibleDate == today) {
                    api.fetchTodayImage(
                        maxWidth = metrics.widthPixels,
                        maxHeight = metrics.heightPixels,
                    )
                } else {
                    api.fetchImageForDate(
                        date = visibleDate,
                        maxWidth = metrics.widthPixels,
                        maxHeight = metrics.heightPixels,
                    )
                }
                try {
                    val target = _uiState.value.wallpaperTarget
                    withContext(Dispatchers.IO) {
                        val wallpaperManager = WallpaperManager.getInstance(getApplication())
                        wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                    }
                    settings.setLastUpdated(LocalDate.now().toString())
                    _uiState.update { it.copy(isSettingWallpaper = false) }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isSettingWallpaper = false) }
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            } catch (e: Exception) {
                _uiState.update { it.copy(isSettingWallpaper = false) }
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_set_wallpaper)))
            }
        }
    }

    fun shareCurrentArtwork() {
        val snapshot = _uiState.value
        val path = if (snapshot.visibleDate == today) "/api/today" else "/api/${snapshot.visibleDate}"
        val artworkUri = Uri.parse("${BauhausApi.BASE_URL}$path")

        val title = snapshot.metadata?.title?.trim().orEmpty()
        val artist = snapshot.metadata?.artist?.trim().orEmpty()
        val metadataText = when {
            title.isNotEmpty() && artist.isNotEmpty() -> "$title — $artist"
            title.isNotEmpty() -> title
            artist.isNotEmpty() -> artist
            else -> null
        }
        val shareText = listOfNotNull(metadataText, artworkUri.toString()).joinToString("\n")
        _shareArtworkEvent.tryEmit(ShareArtworkEvent(uri = artworkUri, text = shareText))
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
                val visibleDate = _uiState.value.visibleDate
                val (bytes, mimeType) = if (visibleDate == today) {
                    api.fetchTodayImageRaw()
                } else {
                    api.fetchImageRawForDate(visibleDate)
                }
                val extension = when (mimeType) {
                    "image/avif" -> "avif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val displayName = "bauhaus_${visibleDate}.$extension"

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Bauhaus")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val resolver = getApplication<Application>().contentResolver
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)) {
                    "MediaStore insert returned null"
                }

                checkNotNull(resolver.openOutputStream(uri)) {
                    "Failed to open output stream for URI: $uri"
                }.use { it.write(bytes) }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                _uiState.update { it.copy(isSavingImage = false) }
                _snackbarEvent.tryEmit(SnackbarEvent("Image saved", uri))
            } catch (e: IOException) {
                _uiState.update { it.copy(isSavingImage = false) }
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            } catch (e: Exception) {
                _uiState.update { it.copy(isSavingImage = false) }
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_save_image)))
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
            _uiState.update {
                it.copy(
                    isRefreshing = true,
                    isMetadataLoading = it.metadata == null,
                    metadataLoadFailed = false,
                )
            }
            try {
                val visibleDate = _uiState.value.visibleDate
                val metadata = if (visibleDate == today) {
                    api.fetchTodayMetadata()
                } else {
                    api.fetchMetadataForDate(visibleDate)
                }
                metadataByDate[visibleDate] = metadata
                _uiState.update {
                    if (it.visibleDate == visibleDate) {
                        it.copy(
                            metadata = metadata,
                            isRefreshing = false,
                            isMetadataLoading = false,
                            metadataLoadFailed = false,
                            imageRevision = it.imageRevision + 1,
                        )
                    } else {
                        it.copy(
                            isRefreshing = false,
                            isMetadataLoading = false,
                            metadataLoadFailed = false,
                        )
                    }
                }
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isMetadataLoading = false,
                        metadataLoadFailed = true,
                    )
                }
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        isMetadataLoading = false,
                        metadataLoadFailed = true,
                    )
                }
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
            }
        }
    }

    private fun loadMetadataForDate(date: LocalDate, force: Boolean) {
        if (!force) {
            metadataByDate[date]?.let { cached ->
                _uiState.update {
                    if (it.visibleDate == date) it.copy(metadata = cached) else it
                }
                return
            }
        }

        viewModelScope.launch {
            _uiState.update {
                if (it.visibleDate == date) {
                    it.copy(isMetadataLoading = true, metadataLoadFailed = false)
                } else {
                    it
                }
            }
            try {
                val metadata = if (date == today) api.fetchTodayMetadata() else api.fetchMetadataForDate(date)
                metadataByDate[date] = metadata
                _uiState.update {
                    if (it.visibleDate == date) {
                        it.copy(metadata = metadata, isMetadataLoading = false, metadataLoadFailed = false)
                    } else {
                        it
                    }
                }
            } catch (_: Exception) {
                _uiState.update {
                    if (it.visibleDate == date) {
                        it.copy(metadata = null, isMetadataLoading = false, metadataLoadFailed = true)
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun appendNextOlderDate() {
        if (isAppendingOlderDate || _uiState.value.reachedArchiveStart) return
        val oldest = allBrowsableDates.lastOrNull() ?: today
        val nextOlderDate = oldest.minusDays(1)
        isAppendingOlderDate = true

        viewModelScope.launch {
            try {
                val metadata = api.fetchMetadataForDate(nextOlderDate)
                metadataByDate[nextOlderDate] = metadata
                allBrowsableDates = allBrowsableDates + nextOlderDate
                _uiState.update { state ->
                    if (!state.showFavoritesOnly) {
                        state.copy(availableDates = allBrowsableDates)
                    } else {
                        state
                    }
                }
            } catch (e: BauhausHttpException) {
                if (e.code == 404) {
                    _uiState.update { it.copy(reachedArchiveStart = true) }
                } else {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                }
            } catch (e: IOException) {
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
            } finally {
                isAppendingOlderDate = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "APPLICATION_KEY not found in CreationExtras"
                }
                BauhausViewModel(
                    app,
                    SettingsRepository(app),
                    BauhausApi(HttpModule.client(app)),
                )
            }
        }
    }
}
