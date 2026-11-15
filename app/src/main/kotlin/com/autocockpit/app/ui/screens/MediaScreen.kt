package com.autocockpit.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autocockpit.app.ui.UiTags
import com.autocockpit.app.ui.vm.MediaBrowserViewModel
import com.autocockpit.app.ui.vm.MediaUiState

@Composable
fun MediaScreen(
    viewModel: MediaBrowserViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.MEDIA_SCREEN)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.canGoBack) {
                IconButton(
                    onClick = { viewModel.back() },
                    modifier = Modifier.testTag(UiTags.MEDIA_BACK),
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (state.error != null) {
            Text(
                text = state.error.orEmpty(),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Text(
            text = if (state.connected) "Media browser connected" else "Connecting to media service…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
            items(state.nodes, key = { it.id }) { node ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (node.playable) viewModel.play(node.id) else viewModel.browse(node.id, node.title)
                        }
                        .testTag(UiTags.mediaNode(node.id))
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        node.subtitle?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        text = if (node.playable) "Play" else "›",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        NowPlayingBar(state = state, viewModel = viewModel)
    }
}

@Composable
private fun NowPlayingBar(state: MediaUiState, viewModel: MediaBrowserViewModel) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.MEDIA_NOW_PLAYING),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.nowPlayingTitle ?: "Nothing playing",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.nowPlayingArtist ?: "Choose a track to start",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = viewModel::skipPrevious,
                    modifier = Modifier.testTag(UiTags.MEDIA_SKIP_PREVIOUS),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.testTag(UiTags.MEDIA_PLAY_TOGGLE),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = viewModel::skipNext,
                    modifier = Modifier.testTag(UiTags.MEDIA_SKIP_NEXT),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
