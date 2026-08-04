package com.cascadiacollections.bauhaus.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * App-wide Material 3 theme using **dynamic color** (Material You).
 *
 * Since the app targets min SDK 35, dynamic color is always available — no
 * fallback palette is needed. The color scheme is derived from the user's
 * current wallpaper, which creates a nice feedback loop: the bauhaus artwork
 * this app sets as the wallpaper influences the app's own color scheme on the
 * next launch.
 *
 * System bar icon appearance is left to `enableEdgeToEdge()` in
 * [MainActivity][com.cascadiacollections.bauhaus.MainActivity]. Its default
 * `SystemBarStyle.auto()` already tracks the system dark-mode setting, which is
 * the same signal [isSystemInDarkTheme] reads, so setting the appearance again
 * here only duplicated it — and did so through a `view.context as Activity`
 * cast that throws in any non-Activity ComposeView host.
 */
@Composable
fun BauhausTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
