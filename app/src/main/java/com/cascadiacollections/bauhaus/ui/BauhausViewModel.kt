package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.app.WallpaperManager
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cascadiacollections.bauhaus.AppContainerProvider
import com.cascadiacollections.bauhaus.CrashReporter
import com.cascadiacollections.bauhaus.WallpaperScheduler
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.SettingsStore
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap

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
    private val settings: SettingsStore,
    private val api: BauhausApiClient,
    private val scheduler: WallpaperScheduler,
) : AndroidViewModel(application) {
    private val maxJumpExpansionDays: Long = 730
    private val archiveFetchConcurrency: Int = 4

    /** Always use [currentToday] instead of caching LocalDate.now() to handle midnight rollover. */
    private val currentToday: LocalDate get() = LocalDate.now()

    private val archiveMutex = Mutex()
    private val metadataByDate = object : LinkedHashMap<LocalDate, ArtworkMetadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, ArtworkMetadata>): Boolean {
            return size > MAX_METADATA_CACHE_SIZE
        }
    }
    private var isAppendingOlderDate: Boolean = false

    /**
     * Tracks the full chronological list of dates available for browsing
     * (independent of the favorites filter). Restored when the user exits
     * favorites-only mode.
     */
    private var allBrowsableDates: List<LocalDate> = listOf(currentToday)

    /** Minimum milliseconds between user-initiated refreshes (DOS guard). */
    private val refreshCooldownMs: Long = 30_000L
    private var lastRefreshAt: Long = 0L

    private fun getString(@androidx.annotation.StringRes resId: Int): String =
        getApplication<Application>().getString(resId)

    private val _uiState = MutableStateFlow(
        UiState(
            visibleDate = currentToday,
            availableDates = listOf(currentToday),
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
                metadataByDate[currentToday] = metadata
                _uiState.update { it.copy(metadata = metadata, isMetadataLoading = false, metadataLoadFailed = false) }
            } catch (_: IOException) {
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
        if (date.isAfter(currentToday)) return
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

        val oldestLoadedDate = snapshot.availableDates.lastOrNull() ?: currentToday
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
            archiveMutex.withLock {
                val datesToLoad = buildList {
                    var cursor = oldestLoadedDate.minusDays(1)
                    while (cursor >= date && size < maxJumpExpansionDays.toInt()) {
                        add(cursor)
                        cursor = cursor.minusDays(1)
                    }
                }

                val appendedDates = mutableListOf<LocalDate>()
                var reachedArchiveStart = false
                for ((archiveDate, result) in fetchArchiveMetadata(datesToLoad)) {
                    val throwable = result.exceptionOrNull()
                    if (throwable != null) {
                        when (throwable) {
                            is BauhausHttpException -> {
                                if (throwable.code == 404) {
                                    reachedArchiveStart = true
                                    break
                                }
                                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                                return@withLock
                            }
                            is IOException -> {
                                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
                                return@withLock
                            }
                            else -> {
                                CrashReporter.recordException(throwable)
                                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                                return@withLock
                            }
                        }
                    }

                    metadataByDate[archiveDate] = result.getOrThrow()
                    appendedDates += archiveDate
                    if (archiveDate == date) break
                }

                val targetReached = appendedDates.contains(date)
                if (appendedDates.isNotEmpty()) {
                    allBrowsableDates = allBrowsableDates + appendedDates
                }
                _uiState.update {
                    val mergedDates = if (appendedDates.isNotEmpty()) {
                        it.availableDates + appendedDates
                    } else {
                        it.availableDates
                    }
                    if (targetReached) {
                        it.copy(
                            availableDates = if (it.showFavoritesOnly) it.availableDates else mergedDates,
                            visibleDate = date,
                            metadata = metadataByDate[date],
                            reachedArchiveStart = it.reachedArchiveStart || reachedArchiveStart,
                        )
                    } else {
                        it.copy(
                            availableDates = if (it.showFavoritesOnly) it.availableDates else mergedDates,
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
        viewModelScope.launch {
            settings.setSchedulingEnabled(enabled)
            if (enabled) scheduler.scheduleDaily() else scheduler.cancelDaily()
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
                val bitmap = if (visibleDate == currentToday) {
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
            } catch (_: IOException) {
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
        val path = if (snapshot.visibleDate == currentToday) "/api/today" else "/api/${snapshot.visibleDate}"
        val artworkUri = "${BauhausApi.BASE_URL}$path".toUri()

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
            val resolver = getApplication<Application>().contentResolver
            var pendingUri: Uri? = null
            var saveSucceeded = false
            try {
                val visibleDate = _uiState.value.visibleDate
                val (bytes, mimeType) = if (visibleDate == currentToday) {
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

                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)) {
                    "MediaStore insert returned null"
                }
                pendingUri = uri

                checkNotNull(resolver.openOutputStream(uri)) {
                    "Failed to open output stream for URI: $uri"
                }.use { it.write(bytes) }

                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                saveSucceeded = true
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.image_saved), uri))
            } catch (_: IOException) {
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_save_image)))
            } finally {
                if (!saveSucceeded) {
                    pendingUri?.let { uri ->
                        runCatching { resolver.delete(uri, null, null) }
                            .onFailure { CrashReporter.recordException(it) }
                    }
                }
                _uiState.update { it.copy(isSavingImage = false) }
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
                val metadata = if (visibleDate == currentToday) {
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
            } catch (_: IOException) {
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
                val metadata = if (date == currentToday) api.fetchTodayMetadata() else api.fetchMetadataForDate(date)
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

    private suspend fun fetchArchiveMetadata(
        datesToLoad: List<LocalDate>,
    ): List<Pair<LocalDate, Result<ArtworkMetadata>>> = coroutineScope {
        val semaphore = Semaphore(archiveFetchConcurrency)
        datesToLoad.map { archiveDate ->
            async {
                semaphore.withPermit {
                    archiveDate to runCatching { api.fetchMetadataForDate(archiveDate) }
                }
            }
        }.awaitAll()
    }

    private fun appendNextOlderDate() {
        if (isAppendingOlderDate || _uiState.value.reachedArchiveStart) return
        val oldest = allBrowsableDates.lastOrNull() ?: currentToday
        val nextOlderDate = oldest.minusDays(1)
        isAppendingOlderDate = true

        viewModelScope.launch {
            archiveMutex.withLock {
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
                } catch (_: IOException) {
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_network)))
                } catch (e: Exception) {
                    CrashReporter.recordException(e)
                    _snackbarEvent.tryEmit(SnackbarEvent(getString(R.string.error_refresh)))
                } finally {
                    isAppendingOlderDate = false
                }
            }
        }
    }

    companion object {
        private const val MAX_METADATA_CACHE_SIZE = 256

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "APPLICATION_KEY not found in CreationExtras"
                }
                val containerProvider = app as? AppContainerProvider
                    ?: error("Application must implement AppContainerProvider")
                val container = containerProvider.container
                BauhausViewModel(
                    app,
                    container.settingsRepository,
                    container.bauhausApi,
                    container.wallpaperScheduler,
                )
            }
        }
    }
}
