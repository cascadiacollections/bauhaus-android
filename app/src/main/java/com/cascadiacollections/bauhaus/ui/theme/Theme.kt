package com.cascadiacollections.bauhaus.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * App-wide Material 3 theme using **dynamic color** (Material You).
 *
 * Since the app targets min SDK 35, dynamic color is always available — no
 * fallback palette is needed. The color scheme is derived from the user's
 * current wallpaper, which creates a nice feedback loop: the bauhaus artwork
 * this app sets as the wallpaper influences the app's own color scheme on the
 * next launch.
 *
 * Status bar appearance is synchronized with the theme so icons remain
 * legible in both light and dark modes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
