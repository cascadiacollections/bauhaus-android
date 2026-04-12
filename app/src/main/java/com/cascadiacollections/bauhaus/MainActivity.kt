package com.cascadiacollections.bauhaus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.cascadiacollections.bauhaus.ui.BauhausViewModel
import com.cascadiacollections.bauhaus.ui.SettingsScreen
import com.cascadiacollections.bauhaus.ui.SettingsScreenTestTags
import com.cascadiacollections.bauhaus.ui.theme.BauhausTheme

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

                LaunchedEffect(Unit) {
                    viewModel.snackbarEvent.collect { event ->
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.uri?.let { "Open" },
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed && event.uri != null) {
                            startActivity(Intent(Intent.ACTION_VIEW, event.uri))
                        }
                    }
                }

                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            actions = {
                                val uiState by viewModel.uiState.collectAsState()
                                IconButton(
                                    onClick = viewModel::saveImageToGallery,
                                    enabled = !uiState.isSavingImage,
                                    modifier = Modifier.semantics {
                                        testTag = SettingsScreenTestTags.DOWNLOAD_ICON
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Download,
                                        contentDescription = stringResource(R.string.save_image),
                                    )
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    SettingsScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
