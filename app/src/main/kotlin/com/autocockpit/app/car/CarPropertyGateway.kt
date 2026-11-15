package com.autocockpit.app.car

import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.VehicleSignal
import kotlinx.coroutines.flow.StateFlow

/** Where samples come from and whether the cockpit is running against real car hardware. */
data class GatewayInfo(
    val available: Boolean,
    val implementation: String,
    val fallbackReason: String? = null,
) {
    val isFallback: Boolean get() = fallbackReason != null
}

sealed interface WriteResult {
    data object Accepted : WriteResult
    data class Rejected(val reason: String) : WriteResult
    data object Unsupported : WriteResult
}

/**
 * Abstraction boundary between AAOS CarPropertyManager and the rest of the app. Everything above
 * this interface is testable with [FakeCarPropertyGateway] and Robolectric.
 */
interface CarPropertyGateway {
    val info: GatewayInfo

    /** Latest sample per signal. Emission happens on the thread that observed the change. */
    val signals: StateFlow<Map<VehicleSignal, SignalSample>>

    fun supportedSignals(): Set<VehicleSignal>

    fun start()

    fun stop()

    /** Writes HVAC_TEMPERATURE_SET; returns [WriteResult.Unsupported] when the gateway cannot. */
    fun writeHvac(tempCelsius: Double): WriteResult
}
