package com.autocockpit.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autocockpit.app.ui.UiTags
import com.autocockpit.app.ui.vm.GaugeViewModel

/** Placeholder body replaced by the OpenGL surface in step 4. */
@Composable
fun GaugeScreen(viewModel: GaugeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier.fillMaxSize().testTag(UiTags.GAUGE_SCREEN),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${state.headline} km/h",
            style = MaterialTheme.typography.displayLarge,
        )
    }
}
