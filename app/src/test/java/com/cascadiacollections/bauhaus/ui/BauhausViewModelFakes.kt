package com.cascadiacollections.bauhaus.ui

import android.content.Context
import android.graphics.Bitmap
import com.cascadiacollections.bauhaus.WallpaperScheduler
import com.cascadiacollections.bauhaus.data.ArchiveIndexPage
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApiClient
import com.cascadiacollections.bauhaus.data.BauhausHttpException
import com.cascadiacollections.bauhaus.data.ServiceHealth
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperSetter
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Fakes for BauhausViewModelTest. Adding a method to BauhausApiClient means
// updating FakeBauhausApi here and the fake in WallpaperWorkerTest.

internal class FakeBauhausApi : BauhausApiClient {
    companion object {
        val DEFAULT_METADATA = ArtworkMetadata(title = "Test", artist = "Test Artist")
    }

    var metadataToReturn: ArtworkMetadata = DEFAULT_METADATA
    var shouldThrow = false
    var throwIOException = false
    var healthToReturn: ServiceHealth = ServiceHealth(status = ServiceHealth.STATUS_OK)
    val dateMetadata: MutableMap<LocalDate, ArtworkMetadata> = mutableMapOf()
    val missingDates: MutableSet<LocalDate> = mutableSetOf()

    /** The `before` of every [fetchArchivePage] call, in call order. */
    val archiveRequests: MutableList<LocalDate> = mutableListOf()

    /** Oldest day the fake archive has; every later day exists unless in [missingDates]. */
    var archiveStart: LocalDate = LocalDate.of(2020, 1, 1)

    /** Dates per archive page, mirroring the service's `?limit=`. */
    var archivePageSize = 1000

    /** Dates whose metadata was actually fetched, in call order. */
    val fetchedMetadataDates: MutableList<LocalDate> = mutableListOf()

    override suspend fun fetchArchivePage(before: LocalDate): ArchiveIndexPage {
        archiveRequests += before
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        val older = generateSequence(before.minusDays(1)) { it.minusDays(1) }
            .takeWhile { !it.isBefore(archiveStart) }
            .filter { it !in missingDates }
        val page = older.take(archivePageSize + 1).toList()
        val dates = page.take(archivePageSize)
        return ArchiveIndexPage(
            dates = dates.map(LocalDate::toString),
            next = if (page.size > archivePageSize) "/api/archive?before=${dates.last()}" else null
        )
    }

    /** Number of times [fetchHealth] has been called. */
    var healthCalls = 0

    /** Thrown from [fetchHealth] when set, simulating an unreachable probe. */
    var healthError: Throwable? = null

    override suspend fun fetchHealth(): ServiceHealth {
        healthCalls++
        healthError?.let { throw it }
        return healthToReturn
    }

    /** Thrown from [fetchTodayMetadata] when set, in preference to the flags above. */
    var todayMetadataError: Throwable? = null

    /** Number of times [fetchTodayMetadata] has been entered. */
    var todayMetadataCalls = 0

    /** When set, [fetchTodayMetadata] parks until it completes, simulating a slow service. */
    var todayMetadataGate: CompletableDeferred<Unit>? = null

    override suspend fun fetchTodayMetadata(): ArtworkMetadata {
        todayMetadataCalls++
        todayMetadataGate?.await()
        todayMetadataError?.let { throw it }
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        return metadataToReturn
    }

    /** Dates whose [fetchMetadataForDate] parks until the deferred completes. */
    val dateMetadataGates: MutableMap<LocalDate, CompletableDeferred<Unit>> = mutableMapOf()

    override suspend fun fetchMetadataForDate(date: LocalDate): ArtworkMetadata {
        fetchedMetadataDates += date
        dateMetadataGates[date]?.await()
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date.json")
        return dateMetadata[date] ?: ArtworkMetadata(title = "Date $date", artist = "Archive")
    }

    /** Number of times [fetchTodayImage] has been called. */
    var todayImageCalls = 0

    /** Dates passed to [fetchImageForDate], in call order. */
    val imageDates: MutableList<LocalDate> = mutableListOf()

    override suspend fun fetchTodayImage(maxWidth: Int, maxHeight: Int): Bitmap {
        todayImageCalls++
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override suspend fun fetchImageForDate(date: LocalDate, maxWidth: Int, maxHeight: Int): Bitmap {
        imageDates += date
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override suspend fun fetchTodayImageRaw(): Pair<ByteArray, String> {
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        return byteArrayOf(0) to "image/jpeg"
    }

    override suspend fun fetchImageRawForDate(date: LocalDate): Pair<ByteArray, String> {
        if (throwIOException) throw java.io.IOException("Unable to resolve host")
        if (shouldThrow) throw RuntimeException("Unexpected error")
        if (missingDates.contains(date)) throw BauhausHttpException(404, "/api/$date")
        return byteArrayOf(0) to "image/jpeg"
    }
}

internal class RecordingWallpaperSetter : WallpaperSetter {
    var calls = 0

    override suspend fun set(context: Context, bitmap: Bitmap, target: WallpaperTarget) {
        calls++
    }
}

internal class FakeWallpaperScheduler : WallpaperScheduler {
    var scheduled = false
    var cancelled = false
    var immediateRequests = 0

    override fun scheduleDaily() {
        scheduled = true
    }

    override fun cancelDaily() {
        cancelled = true
    }

    override fun requestImmediateUpdate() {
        immediateRequests++
    }
}

internal class FakeSettingsRepository(context: Context) : SettingsRepository(context) {
    private val _wallpaperTarget = MutableStateFlow(WallpaperTarget.BOTH)
    override val wallpaperTarget: Flow<WallpaperTarget> = _wallpaperTarget

    private val _schedulingEnabled = MutableStateFlow(true)
    override val schedulingEnabled: Flow<Boolean> = _schedulingEnabled

    private val _lastUpdated = MutableStateFlow<String?>(null)
    override val lastUpdated: Flow<String?> = _lastUpdated

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    override val favorites: Flow<Set<String>> = _favorites

    val favoriteDatesSet: Set<String> get() = _favorites.value

    var lastSetTarget: WallpaperTarget? = null

    fun emitWallpaperTarget(target: WallpaperTarget) {
        _wallpaperTarget.value = target
    }
    fun emitSchedulingEnabled(enabled: Boolean) {
        _schedulingEnabled.value = enabled
    }
    fun emitLastUpdated(date: String?) {
        _lastUpdated.value = date
    }

    override suspend fun setWallpaperTarget(target: WallpaperTarget) {
        lastSetTarget = target
        _wallpaperTarget.value = target
    }

    override suspend fun setSchedulingEnabled(enabled: Boolean) {
        _schedulingEnabled.value = enabled
    }

    override suspend fun setLastUpdated(date: String) {
        _lastUpdated.value = date
    }

    override suspend fun toggleFavorite(date: String) {
        _favorites.value = if (date in _favorites.value) {
            _favorites.value - date
        } else {
            _favorites.value + date
        }
    }
}
