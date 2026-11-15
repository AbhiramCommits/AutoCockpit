package com.autocockpit.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.autocockpit.app.ui.UiTags
import com.autocockpit.app.ui.vm.SettingsUiState
import com.autocockpit.app.ui.vm.SettingsViewModel
import com.autocockpit.app.ui.vm.SignalRow
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.link.LinkState
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(
            com.autocockpit.app.AppGraph.get(
                androidx.compose.ui.platform.LocalContext.current.applicationContext,
            ),
        ),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.SETTINGS_SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Vehicle", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)

        if (state.gateway.isFallback) {
            FallbackBanner(state.gateway.fallbackReason.orEmpty())
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.rows.forEach { row -> SignalRowView(row) }
        }

        HvacCard(state = state, onApply = viewModel::writeHvac)
        LinkCard(state = state, onResync = viewModel::resync, onReconnect = viewModel::attemptReconnect)
        DriverCard(state)
    }
}

@Composable
private fun FallbackBanner(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.SETTINGS_GATEWAY_BANNER),
    ) {
        Text(
            text = reason,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.secondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SignalRowView(row: SignalRow) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.signalRow(row.signal::class.simpleName ?: "signal")),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(row.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = buildString {
                        append(row.reason ?: "updated")
                        if (row.ageMillis >= 0) append(" · ${row.ageMillis / 1000}s ago")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (row.unit.isBlank()) row.displayValue else "${row.displayValue} ${row.unit}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 12.dp),
            )
            TierBadge(row)
        }
    }
}

@Composable
private fun TierBadge(row: SignalRow) {
    val (label, color) = when (row.tier) {
        UiTier.LIVE -> "LIVE" to Color(0xFF43D17C)
        UiTier.LAST_KNOWN -> "LAST KNOWN ${row.ageSeconds.coerceAtLeast(0)}s" to Color(0xFFFFB74D)
        UiTier.UNAVAILABLE -> "UNAVAILABLE" to Color(0xFF8A97A5)
    }
    Surface(color = color.copy(alpha = 0.16f), shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun HvacCard(state: SettingsUiState, onApply: (Float) -> String?) {
    var target by remember(state.hvacTarget) { mutableFloatStateOf(state.hvacTarget) }
    var error by remember { mutableStateOf<String?>(null) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Cabin temperature", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "%.1f °C".format(target),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(UiTags.SETTINGS_HVAC_VALUE),
            )
            Slider(
                value = target,
                onValueChange = { target = it },
                valueRange = SettingsViewModel.MIN_HVAC_C..SettingsViewModel.MAX_HVAC_C,
                steps = 23,
                modifier = Modifier.testTag(UiTags.SETTINGS_HVAC_SLIDER),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { error = onApply((target * 10f).roundToInt() / 10f) },
                modifier = Modifier.testTag(UiTags.SETTINGS_HVAC_APPLY),
            ) {
                Text("Set cabin temperature")
            }
        }
    }
}

@Composable
private fun LinkCard(
    state: SettingsUiState,
    onResync: () -> Unit,
    onReconnect: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Companion phone", style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.link.state.name,
                style = MaterialTheme.typography.headlineSmall,
                color = when (state.link.state) {
                    LinkState.SYNCED -> Color(0xFF43D17C)
                    LinkState.DEGRADED -> Color(0xFFFFB74D)
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.testTag(UiTags.SETTINGS_LINK_STATE),
            )
            Text(
                text = "attempt ${state.link.attempt} / ${state.link.mtu} MTU · ${(state.link.progress * 100).roundToInt()}% synced",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.SETTINGS_LINK_ATTEMPT),
            )
            state.link.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(onClick = onResync, modifier = Modifier.testTag(UiTags.SETTINGS_RESYNC)) {
                    Text("Re-sync")
                }
                OutlinedButton(onClick = onReconnect, modifier = Modifier.testTag(UiTags.SETTINGS_RECONNECT)) {
                    Text("Reconnect")
                }
            }
        }
    }
}

@Composable
private fun DriverCard(state: SettingsUiState) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Driver profile", style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.handoff.driverName ?: "No handoff received",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = buildString {
                    append(state.handoff.seat ?: "seat unknown")
                    append(" · ")
                    append("${state.handoff.trackCount} queued tracks")
                    append(" · revision ${state.handoff.revision}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
