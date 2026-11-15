package com.autocockpit.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.autocockpit.app.AppGraph
import com.autocockpit.app.car.CarPropertyGateway
import com.autocockpit.app.car.GatewayInfo
import com.autocockpit.app.car.WriteResult
import com.autocockpit.app.clock.Clock
import com.autocockpit.app.link.CompanionLinkController
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import com.autocockpit.core.handoff.HandoffRepository
import com.autocockpit.core.health.DegradationPolicy
import com.autocockpit.core.health.PropertyHealthMonitor
import com.autocockpit.core.link.LinkSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One row on the vehicle settings screen. */
data class SignalRow(
    val signal: VehicleSignal,
    val label: String,
    val unit: String,
    val tier: UiTier,
    val displayValue: String,
    val rawValue: Double?,
    val ageMillis: Long,
    val lastStatus: SampleStatus?,
    val reason: String?,
) {
    val ageSeconds: Long get() = if (ageMillis < 0) -1 else ageMillis / 1000
}

data class HandoffUiInfo(
    val driverName: String?,
    val seat: String?,
    val trackCount: Int,
    val revision: Int,
    val generatedAtMillis: Long?,
)

data class SettingsUiState(
    val rows: List<SignalRow>,
    val link: LinkSnapshot,
    val gateway: GatewayInfo,
    val handoff: HandoffUiInfo,
    val hvacTarget: Float,
    val asOfMillis: Long,
    val message: String? = null,
)

/**
 * Settings screen state. Applies [PropertyHealthMonitor] to the gateway flow, so a mid-drive
 * property dropout visibly walks LIVE -> LAST_KNOWN -> UNAVAILABLE and recovers when data returns.
 */
class SettingsViewModel(
    private val gateway: CarPropertyGateway,
    private val handoffRepository: HandoffRepository,
    private val link: CompanionLinkController,
    private val clock: Clock,
    private val gatewayInfo: GatewayInfo = gateway.info,
) : ViewModel() {

    private val policy = DegradationPolicy()
    private val monitor = PropertyHealthMonitor(policy)

    private var handoff: HandoffPayload? = handoffRepository.current()
    private var handoffSubscription: AutoCloseable? = null
    private var hvacTarget: Float = handoff?.profile?.hvacSetpointC ?: DEFAULT_HVAC_C

    private val _state = MutableStateFlow(stateAt(clock.nowMillis()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        handoffRepository.current()?.let { payload ->
            payload.profile.hvacSetpointC.toFloat().let { hvacTarget = it }
        }
        viewModelScope.launch {
            gateway.signals.collect { samples ->
                samples.values.forEach(monitor::onSample)
                refresh()
            }
        }
        viewModelScope.launch {
            link.state.collect { refresh() }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
        handoffSubscription = handoffRepository.observe { payload ->
            handoff = payload
            payload?.let { hvacTarget = it.profile.hvacSetpointC.toFloat() }
            refresh()
        }
        refresh()
    }

    fun refresh(nowMillis: Long = clock.nowMillis()) {
        _state.value = stateAt(nowMillis)
    }

    /** Returns null on success, or a human-readable rejection reason. */
    fun writeHvac(tempCelsius: Float): String? {
        if (tempCelsius !in MIN_HVAC_C..MAX_HVAC_C) {
            return "Target outside ${MIN_HVAC_C.toInt()}..${MAX_HVAC_C.toInt()} °C"
        }
        return when (val result = gateway.writeHvac(tempCelsius.toDouble())) {
            is WriteResult.Accepted -> {
                hvacTarget = tempCelsius
                refresh()
                null
            }
            is WriteResult.Rejected -> result.reason
            WriteResult.Unsupported -> "Cabin temperature control is unavailable in this build"
        }
    }

    fun resync() {
        link.resync { handoffRepository.reapplyLast() }
        refresh()
    }

    fun attemptReconnect() {
        link.reconnectNow()
        refresh()
    }

    override fun onCleared() {
        handoffSubscription?.close()
        super.onCleared()
    }

    private fun stateAt(nowMillis: Long): SettingsUiState {
        val views = monitor.evaluateAll(nowMillis)
        val rows = SIGNAL_ROWS.map { row ->
            val view = views.getValue(row.signal)
            SignalRow(
                signal = row.signal,
                label = row.label,
                unit = row.unit,
                tier = view.tier,
                displayValue = formatValue(row.signal, view.value, view.tier),
                rawValue = view.value,
                ageMillis = view.ageMillis,
                lastStatus = view.lastStatus,
                reason = view.reason,
            )
        }
        val payload = handoff
        return SettingsUiState(
            rows = rows,
            link = link.state.value,
            gateway = gatewayInfo,
            handoff = HandoffUiInfo(
                driverName = payload?.profile?.displayName,
                seat = payload?.profile?.seat?.name,
                trackCount = payload?.queue?.tracks?.size ?: 0,
                revision = handoffRepository.revision(),
                generatedAtMillis = payload?.generatedAtMillis,
            ),
            hvacTarget = hvacTarget,
            asOfMillis = nowMillis,
        )
    }

    private fun formatValue(signal: VehicleSignal, value: Double?, tier: UiTier): String {
        if (value == null) return "—"
        val numeric = when (signal) {
            is VehicleSignal.Speed -> "%.0f".format(value)
            is VehicleSignal.BatteryLevel -> "%.0f".format(value)
            is VehicleSignal.RangeKm -> "%.0f".format(value)
            is VehicleSignal.HvacTemp -> "%.1f".format(value)
            is VehicleSignal.ChargePortOpen -> if (value >= 0.5) "Open" else "Closed"
        }
        return if (tier == UiTier.UNAVAILABLE && signal !is VehicleSignal.ChargePortOpen) "—" else numeric
    }

    private data class RowSpec(val signal: VehicleSignal, val label: String, val unit: String)

    companion object {
        const val REFRESH_INTERVAL_MS: Long = 250L
        const val MIN_HVAC_C: Float = 16f
        const val MAX_HVAC_C: Float = 28f
        const val DEFAULT_HVAC_C: Float = 21.5f

        private val SIGNAL_ROWS = listOf(
            RowSpec(VehicleSignal.Speed, "Vehicle speed", "km/h"),
            RowSpec(VehicleSignal.BatteryLevel, "Battery", "%"),
            RowSpec(VehicleSignal.RangeKm, "Range", "km"),
            RowSpec(VehicleSignal.HvacTemp, "Cabin temperature", "°C"),
            RowSpec(VehicleSignal.ChargePortOpen, "Charge port", ""),
        )

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    gateway = graph.gateway,
                    handoffRepository = graph.handoffRepository,
                    link = graph.linkController,
                    clock = graph.clock,
                    gatewayInfo = graph.gatewayInfo,
                )
            }
        }
    }
}
