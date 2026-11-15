package com.autocockpit.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autocockpit.app.AppGraph
import com.autocockpit.app.car.CarPropertyGateway
import com.autocockpit.app.clock.Clock
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import com.autocockpit.core.health.DegradationPolicy
import com.autocockpit.core.health.PropertyHealthMonitor
import com.autocockpit.core.health.SignalScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GaugeUiState(
    /** Smoothed 0..1 values the renderer consumes. */
    val speedNormalized: Float = 0f,
    val batteryNormalized: Float = 0.5f,
    val rangeNormalized: Float = 0.5f,
    val speedKmh: Double = 0.0,
    val batteryPct: Double = 0.0,
    val rangeKm: Double = 0.0,
    val speedTier: UiTier = UiTier.UNAVAILABLE,
    val batteryTier: UiTier = UiTier.UNAVAILABLE,
    val degraded: Boolean = true,
    val label: String = "UNAVAILABLE",
    val hvacC: Double = 21.5,
) {
    val headline: String get() = "%.0f".format(speedKmh)
}

/**
 * Gauge screen state. Smooths the speed signal for the arc, keeps a tier label for the OpenGL
 * overlay, and never blocks on the car bus: a missing signal just degrades.
 */
class GaugeViewModel(
    private val gateway: CarPropertyGateway,
    private val clock: Clock,
    private val policy: DegradationPolicy = DegradationPolicy(staleAfterMillis = 1_500, unavailableAfterMillis = 6_000),
    private val smoothing: Float = 0.18f,
) : ViewModel() {

    private val monitor = PropertyHealthMonitor(policy)
    private val _state = MutableStateFlow(GaugeUiState())
    val state: StateFlow<GaugeUiState> = _state.asStateFlow()

    private var speed = 0f
    private var battery = 0.5f
    private var range = 0.5f
    private var initialised = false

    init {
        viewModelScope.launch {
            gateway.signals.collect { samples ->
                samples.values.forEach(monitor::onSample)
                refresh()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
        refresh()
    }

    fun refresh(nowMillis: Long = clock.nowMillis()) {
        val views = monitor.evaluateAll(nowMillis)
        val speedView = views.getValue(VehicleSignal.Speed)
        val batteryView = views.getValue(VehicleSignal.BatteryLevel)
        val rangeView = views.getValue(VehicleSignal.RangeKm)
        val hvacView = views.getValue(VehicleSignal.HvacTemp)

        val targetSpeed = speedView.value?.let { SignalScale.normalize(VehicleSignal.Speed, it) }?.toFloat()
        val targetBattery = batteryView.value?.let { SignalScale.normalize(VehicleSignal.BatteryLevel, it) }?.toFloat()
        val targetRange = rangeView.value?.let { SignalScale.normalize(VehicleSignal.RangeKm, it) }?.toFloat()

        if (!initialised && targetSpeed != null) {
            // First real reading snaps to the value; later readings are smoothed.
            speed = targetSpeed
            battery = targetBattery ?: 0.5f
            range = targetRange ?: 0.5f
            initialised = true
        } else {
            targetSpeed?.let { speed = lerp(speed, it) }
            targetBattery?.let { battery = lerp(battery, it) }
            targetRange?.let { range = lerp(range, it) }
        }

        val degraded = speedView.tier != UiTier.LIVE
        _state.value = GaugeUiState(
            speedNormalized = speed.coerceIn(0f, 1f),
            batteryNormalized = battery.coerceIn(0f, 1f),
            rangeNormalized = range.coerceIn(0f, 1f),
            speedKmh = speedView.value ?: 0.0,
            batteryPct = batteryView.value ?: 0.0,
            rangeKm = rangeView.value ?: 0.0,
            speedTier = speedView.tier,
            batteryTier = batteryView.tier,
            degraded = degraded,
            label = when (speedView.tier) {
                UiTier.LIVE -> "LIVE"
                UiTier.LAST_KNOWN -> "LAST KNOWN ${(speedView.ageMillis / 1000).coerceAtLeast(0)}s"
                UiTier.UNAVAILABLE -> "UNAVAILABLE"
            },
            hvacC = hvacView.value ?: 21.5,
        )
    }

    private fun lerp(current: Float, target: Float): Float =
        current + (target - current) * smoothing

    companion object {
        const val REFRESH_INTERVAL_MS: Long = 33L

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { GaugeViewModel(graph.gateway, graph.clock) }
        }
    }
}
