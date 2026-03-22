package com.cascadiacollections.bauhaus.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val WALLPAPER_TARGET = stringPreferencesKey("wallpaper_target")
        val SCHEDULING_ENABLED = booleanPreferencesKey("scheduling_enabled")
        val LAST_UPDATED = stringPreferencesKey("last_updated")
    }

    val wallpaperTarget: Flow<WallpaperTarget> = context.dataStore.data.map { prefs ->
        prefs[Keys.WALLPAPER_TARGET]?.let { WallpaperTarget.valueOf(it) } ?: WallpaperTarget.BOTH
    }

    val schedulingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.SCHEDULING_ENABLED] ?: true
    }

    val lastUpdated: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_UPDATED]
    }

    suspend fun setWallpaperTarget(target: WallpaperTarget) {
        context.dataStore.edit { it[Keys.WALLPAPER_TARGET] = target.name }
    }

    suspend fun setSchedulingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SCHEDULING_ENABLED] = enabled }
    }

    suspend fun setLastUpdated(date: String) {
        context.dataStore.edit { it[Keys.LAST_UPDATED] = date }
    }
}
