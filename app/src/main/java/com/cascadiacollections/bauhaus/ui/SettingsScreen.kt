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

/**
 * Main (and only) screen — shows today's bauhaus artwork and wallpaper controls.
 *
 * ## Layout
 *
 * 1. **Preview card** — today's artwork loaded via Coil from the CDN. Uses the
 *    app-wide [ImageLoader][coil3.ImageLoader] (configured in [BauhausApplication][com.cascadiacollections.bauhaus.BauhausApplication])
 *    which negotiates AVIF > WebP > JPEG and caches via the shared OkHttp client.
 * 2. **Metadata** — title and artist from `/api/today.json` (optional; gracefully
 *    hidden if the CDN is unreachable).
 * 3. **Wallpaper target** — Material 3 segmented button row (Home / Lock / Both).
 * 4. **Daily updates toggle** — enables or disables the [WorkManager][androidx.work.WorkManager] periodic job.
 * 5. **"Set Now" button** — immediate wallpaper apply with loading state.
 */
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
        // -- Artwork preview --
        Card(modifier = Modifier.fillMaxWidth()) {
            AsyncImage(
                model = "https://bauhaus.cascadiacollections.workers.dev/api/today",
                contentDescription = stringResource(R.string.todays_artwork),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            )
        }

        // -- Metadata (title + artist) --
        uiState.metadata?.let { metadata ->
            Column {
                Text(
                    text = metadata.title.ifEmpty { stringResource(R.string.daily_bauhaus) },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (metadata.artist.isNotEmpty()) {
                    Text(
                        text = metadata.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // -- Wallpaper target selector --
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

        // -- Daily updates toggle --
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

        uiState.lastUpdated?.let { date ->
            Text(
                text = stringResource(R.string.last_updated, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -- Set wallpaper now --
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

        uiState.error?.let { error ->
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
