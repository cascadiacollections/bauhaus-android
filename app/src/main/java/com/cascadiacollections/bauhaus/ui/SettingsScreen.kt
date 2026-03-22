package com.cascadiacollections.bauhaus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.cascadiacollections.bauhaus.R
import com.cascadiacollections.bauhaus.data.WallpaperTarget

@Composable
fun SettingsScreen(
    viewModel: BauhausViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            AsyncImage(
                model = "https://bauhaus.cascadiacollections.workers.dev/api/today",
                contentDescription = stringResource(R.string.todays_artwork),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )
        }

        if (uiState.metadata != null) {
            Column {
                Text(
                    text = uiState.metadata!!.title.ifEmpty { stringResource(R.string.daily_bauhaus) },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (uiState.metadata!!.artist.isNotEmpty()) {
                    Text(
                        text = uiState.metadata!!.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.wallpaper_target),
            style = MaterialTheme.typography.labelLarge,
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            WallpaperTarget.entries.forEachIndexed { index, target ->
                SegmentedButton(
                    selected = uiState.wallpaperTarget == target,
                    onClick = { viewModel.setWallpaperTarget(target) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = WallpaperTarget.entries.size,
                    ),
                ) {
                    Text(target.label)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.daily_updates),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(
                checked = uiState.schedulingEnabled,
                onCheckedChange = { viewModel.setSchedulingEnabled(it) },
            )
        }

        if (uiState.lastUpdated != null) {
            Text(
                text = stringResource(R.string.last_updated, uiState.lastUpdated!!),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.setWallpaperNow() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isSettingWallpaper,
        ) {
            if (uiState.isSettingWallpaper) {
                CircularProgressIndicator(
                    modifier = Modifier.height(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.set_now))
            }
        }

        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
