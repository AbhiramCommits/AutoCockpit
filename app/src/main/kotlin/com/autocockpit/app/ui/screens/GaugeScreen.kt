package com.autocockpit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.autocockpit.app.ui.UiTags
import com.autocockpit.app.ui.vm.GaugeViewModel
import com.autocockpit.gauge.GaugeView
import java.io.File
import kotlin.math.roundToInt

/**
 * Gauge destination: the OpenGL ES arc gauge composited into Compose via AndroidView, driven by
 * the CarPropertyGateway flow. When the degradation policy reports LAST_KNOWN/UNAVAILABLE the
 * surface desaturates and a tier label replaces the live badge.
 */
@Composable
fun GaugeScreen(viewModel: GaugeViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var gaugeView by remember { mutableStateOf<GaugeView?>(null) }
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkSummary by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().testTag(UiTags.GAUGE_SCREEN)) {
        AndroidView(
            factory = { ctx ->
                GaugeView(ctx).also { view ->
                    view.setValue(state.speedNormalized)
                    view.setBattery(state.batteryNormalized)
                    view.setDegraded(state.degraded)
                    gaugeView = view
                }
            },
            update = { view ->
                view.setValue(state.speedNormalized)
                view.setBattery(state.batteryNormalized)
                view.setDegraded(state.degraded)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.headline,
                style = MaterialTheme.typography.displayLarge,
                color = if (state.degraded) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE6FFF9),
            )
            Text(
                text = "km/h",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.label,
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    state.degraded -> Color(0xFFFFB74D)
                    else -> Color(0xFF43D17C)
                },
                modifier = Modifier.padding(top = 8.dp).testTag(UiTags.GAUGE_TIER_LABEL),
            )
            Text(
                text = "battery ${state.batteryPct.roundToInt()}%  ·  " +
                    "range ${state.rangeKm.roundToInt()} km  ·  " +
                    "cabin ${"%.1f".format(state.hvacC)} °C",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = benchmarkSummary ?: "Benchmark",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(
                checked = benchmarkRunning,
                onCheckedChange = { benchmarkRunning = it },
                modifier = Modifier.testTag(UiTags.GAUGE_BENCHMARK_TOGGLE),
            )
        }
    }

    LaunchedEffect(benchmarkRunning) {
        if (!benchmarkRunning) return@LaunchedEffect
        val view = gaugeView ?: run {
            benchmarkRunning = false
            return@LaunchedEffect
        }
        var frame = 0
        while (frame < BENCHMARK_FRAMES) {
            withFrameNanos { }
            val phase = frame.toFloat() / BENCHMARK_FRAMES
            view.setValue(if (phase < 0.5f) phase * 2f else (1f - phase) * 2f)
            frame++
        }
        val file = view.exportFrameMetrics(File(context.filesDir, FRAME_METRICS_FILE))
        val stats = view.frameStats()
        benchmarkSummary = "frames ${stats.count} · mean %.2f ms · p95 %.2f ms · jank %.1f%% → ${file.name}".format(
            stats.meanMs,
            stats.p95Ms,
            stats.jankPct,
        )
        benchmarkRunning = false
    }
}

private const val BENCHMARK_FRAMES = 1800
private const val FRAME_METRICS_FILE = "gauge_frame_metrics.json"
