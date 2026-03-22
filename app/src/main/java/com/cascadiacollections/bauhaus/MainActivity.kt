package com.cascadiacollections.bauhaus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cascadiacollections.bauhaus.ui.BauhausViewModel
import com.cascadiacollections.bauhaus.ui.SettingsScreen
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

    private val viewModel: BauhausViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BauhausTheme {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                        )
                    },
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
