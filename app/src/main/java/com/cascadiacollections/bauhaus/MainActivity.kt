package com.cascadiacollections.bauhaus

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.net.toUri
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cascadiacollections.bauhaus.ui.BauhausViewModel
import com.cascadiacollections.bauhaus.ui.SettingsScreen
import com.cascadiacollections.bauhaus.ui.SettingsScreenTestTags
import com.cascadiacollections.bauhaus.ui.theme.BauhausTheme
import kotlinx.coroutines.launch

/**
 * Single-activity host for the bauhaus wallpaper app.
 *
 * Uses edge-to-edge rendering and Material 3 dynamic color. The entire UI is
 * a single [SettingsScreen] composable driven by [BauhausViewModel].
 *
 * Architecture is intentionally simple — no navigation graph, no fragments —
 * because this is a single-purpose utility app with one screen.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: BauhausViewModel by viewModels { BauhausViewModel.Factory }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BauhausTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                // repeatOnLifecycle, not a bare LaunchedEffect: composition
                // outlives the Activity being stopped, so a plain collect would
                // consume events into a SnackbarHost nobody can see, and would call
                // startActivity from the background — which the system blocks.
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.snackbarEvent.collect { event ->
                            val result = snackbarHostState.showSnackbar(
                                message = event.message,
                                actionLabel = event.uri?.let { getString(R.string.action_open) },
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                event.uri?.let { uri ->
                                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        viewModel.shareArtworkEvent.collect { event ->
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, event.text)
                            }
                            val chooser = Intent.createChooser(intent, getString(R.string.share_artwork))
                            try {
                                startActivity(chooser)
                            } catch (_: ActivityNotFoundException) {
                                snackbarHostState.showSnackbar(
                                    message = getString(R.string.error_share_unavailable),
                                    duration = SnackbarDuration.Short,
                                )
                            }
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            actions = {
                                IconButton(
                                    onClick = viewModel::shareCurrentArtwork,
                                    enabled = !uiState.isSavingImage,
                                    modifier = Modifier.semantics {
                                        testTag = SettingsScreenTestTags.SHARE_ICON
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = stringResource(R.string.share_artwork),
                                    )
                                }
                                IconButton(
                                    onClick = viewModel::saveImageToGallery,
                                    enabled = !uiState.isSavingImage,
                                    modifier = Modifier.semantics {
                                        testTag = SettingsScreenTestTags.DOWNLOAD_ICON
                                    },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_download),
                                        contentDescription = stringResource(R.string.save_image),
                                    )
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    SettingsScreen(
                        uiState = uiState,
                        onWallpaperTargetChange = viewModel::setWallpaperTarget,
                        onSchedulingToggle = viewModel::setSchedulingEnabled,
                        onSetWallpaperNow = viewModel::setWallpaperNow,
                        onSaveImage = viewModel::saveImageToGallery,
                        onJumpToDate = viewModel::jumpToDate,
                        onFavoriteToggle = viewModel::toggleFavorite,
                        onFavoritesFilterToggle = viewModel::toggleFavoritesFilter,
                        onOpenUrl = { url ->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            } catch (_: ActivityNotFoundException) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        message = getString(R.string.error_open_link_unavailable),
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            }
                        },
                        onArchivePageSelected = viewModel::onArchivePageSelected,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
