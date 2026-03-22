package com.cascadiacollections.bauhaus.ui

import android.app.Application
import android.app.WallpaperManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cascadiacollections.bauhaus.BauhausApplication
import com.cascadiacollections.bauhaus.data.ArtworkMetadata
import com.cascadiacollections.bauhaus.data.BauhausApi
import com.cascadiacollections.bauhaus.data.SettingsRepository
import com.cascadiacollections.bauhaus.data.WallpaperTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UiState(
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.BOTH,
    val schedulingEnabled: Boolean = true,
    val lastUpdated: String? = null,
    val metadata: ArtworkMetadata? = null,
    val isSettingWallpaper: Boolean = false,
    val error: String? = null,
)

class BauhausViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val api = BauhausApi()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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
                // Metadata is optional — don't block UI
            }
        }
    }

    fun setWallpaperTarget(target: WallpaperTarget) {
        viewModelScope.launch {
            settings.setWallpaperTarget(target)
        }
    }

    fun setSchedulingEnabled(enabled: Boolean) {
        val app = getApplication<BauhausApplication>()
        viewModelScope.launch {
            settings.setSchedulingEnabled(enabled)
            if (enabled) {
                app.scheduleWallpaperWorker()
            } else {
                app.cancelWallpaperWorker()
            }
        }
    }

    fun setWallpaperNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSettingWallpaper = true, error = null) }
            try {
                val bitmap = api.fetchTodayImage()
                val target = _uiState.value.wallpaperTarget
                val wallpaperManager = WallpaperManager.getInstance(getApplication())
                wallpaperManager.setBitmap(bitmap, null, true, target.flag)
                settings.setLastUpdated(LocalDate.now().toString())
                _uiState.update { it.copy(isSettingWallpaper = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSettingWallpaper = false, error = e.message ?: "Failed to set wallpaper")
                }
            }
        }
    }
}
