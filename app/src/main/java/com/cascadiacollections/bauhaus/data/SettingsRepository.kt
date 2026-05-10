package com.cascadiacollections.bauhaus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

interface SettingsStore {
    val wallpaperTarget: Flow<WallpaperTarget>
    val schedulingEnabled: Flow<Boolean>
    val lastUpdated: Flow<String?>
    suspend fun isFirstRun(): Boolean
    suspend fun setWallpaperTarget(target: WallpaperTarget)
    suspend fun setSchedulingEnabled(enabled: Boolean)
    suspend fun setLastUpdated(date: String)
    suspend fun getLastPrefetchedDate(): String?
    suspend fun setLastPrefetchedDate(date: String)
    suspend fun markFirstRunComplete()
}

/**
 * Persists user preferences via Jetpack DataStore (Preferences).
 *
 * All writes are atomic and non-blocking. Reads are exposed as [Flow]s that
 * emit the current value on collection and again whenever the underlying store
 * changes — this drives reactive UI updates in the ViewModel.
 *
 * ## Keys
 *
 * | Key | Type | Default | Purpose |
 * |-----|------|---------|---------|
 * | `wallpaper_target` | String (enum name) | `BOTH` | Which screen(s) to wallpaper |
 * | `scheduling_enabled` | Boolean | `true` | Whether the daily WorkManager job is active |
 * | `last_updated` | String (ISO date) | `null` | Date of the most recent wallpaper set |
 * | `last_prefetched_date` | String (ISO date) | `null` | Date when startup prefetch last warmed `/api/today` |
 * | `first_run` | Boolean | `true` | Guards the expedited first-launch fetch |
 *
 * @param context Application context — the DataStore file lives in the app's
 *                private data directory and is excluded from cloud backup via
 *                `backup_rules.xml`.
 */
open class SettingsRepository(private val context: Context) : SettingsStore {

    private object Keys {
        val WALLPAPER_TARGET = stringPreferencesKey("wallpaper_target")
        val SCHEDULING_ENABLED = booleanPreferencesKey("scheduling_enabled")
        val LAST_UPDATED = stringPreferencesKey("last_updated")
        val LAST_PREFETCHED_DATE = stringPreferencesKey("last_prefetched_date")
        val FIRST_RUN = booleanPreferencesKey("first_run")
    }

    /** Which screen(s) the wallpaper should be applied to. */
    override val wallpaperTarget: Flow<WallpaperTarget> = context.dataStore.data.map { prefs ->
        prefs[Keys.WALLPAPER_TARGET]?.let { WallpaperTarget.valueOf(it) } ?: WallpaperTarget.BOTH
    }

    /** Whether the daily periodic WorkManager job is enabled. */
    override val schedulingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCHEDULING_ENABLED] ?: true
    }

    /** ISO-8601 date string of the last successful wallpaper update, or `null`. */
    override val lastUpdated: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_UPDATED]
    }

    /** `true` on the very first app launch; `false` after the expedited worker runs. */
    override suspend fun isFirstRun(): Boolean =
        context.dataStore.data.first()[Keys.FIRST_RUN] ?: true

    override suspend fun setWallpaperTarget(target: WallpaperTarget) {
        context.dataStore.edit { it[Keys.WALLPAPER_TARGET] = target.name }
    }

    override suspend fun setSchedulingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SCHEDULING_ENABLED] = enabled }
    }

    override suspend fun setLastUpdated(date: String) {
        context.dataStore.edit { it[Keys.LAST_UPDATED] = date }
    }

    override suspend fun getLastPrefetchedDate(): String? =
        context.dataStore.data.first()[Keys.LAST_PREFETCHED_DATE]

    override suspend fun setLastPrefetchedDate(date: String) {
        context.dataStore.edit { it[Keys.LAST_PREFETCHED_DATE] = date }
    }

    /** Marks first-run as complete so the expedited worker is not re-enqueued. */
    override suspend fun markFirstRunComplete() {
        context.dataStore.edit { it[Keys.FIRST_RUN] = false }
    }
}
