package com.unshoo.pixelmusic.presentation.components.player

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.presentation.viewmodel.VisualizerViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RootBeatVisualizer(
    modifier: Modifier = Modifier,
    viewModel: VisualizerViewModel = hiltViewModel(),
) {
    val bars by viewModel.bars.collectAsStateWithLifecycle()
    val permission = rememberPermissionState(Manifest.permission.RECORD_AUDIO)
    LaunchedEffect(permission.status.isGranted) {
        if (permission.status.isGranted) viewModel.retry()
    }
    val color = MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            val count = bars.size.coerceAtLeast(1)
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
            bars.forEachIndexed { index, level ->
                val minimum = 6.dp.toPx()
                val barHeight = minimum + (size.height - minimum) * level.coerceIn(0f, 1f)
                val left = index * (barWidth + gap)
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
        if (!permission.status.isGranted) {
            TextButton(onClick = { permission.launchPermissionRequest() }) {
                Text(stringResource(R.string.jam_visualizer_enable))
            }
        }
    }
}
