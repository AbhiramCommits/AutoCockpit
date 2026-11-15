package com.autocockpit.app.car

import com.autocockpit.app.clock.Clock
import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.VehicleSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Deterministic property feed used by unit tests, the emulator fallback and the instrumented
 * suite. Tests call [emit] and advance a test clock; nothing here touches Android car APIs.
 */
class FakeCarPropertyGateway(
    private val clock: Clock,
    initialValues: Map<VehicleSignal, Double> = DEFAULT_VALUES,
    private val supported: Set<VehicleSignal> = DEFAULT_VALUES.keys,
) : CarPropertyGateway {

    private val _signals = MutableStateFlow(
        initialValues.mapValues { (signal, value) ->
            SignalSample(signal, value, clock.nowMillis(), SampleStatus.AVAILABLE)
        },
    )

    override val signals: StateFlow<Map<VehicleSignal, SignalSample>> = _signals.asStateFlow()

    override val info: GatewayInfo = GatewayInfo(
        available = true,
        implementation = "FakeCarPropertyGateway",
        fallbackReason = null,
    )

    private var started = false

    override fun supportedSignals(): Set<VehicleSignal> = supported

    override fun start() {
        started = true
        emitAll(DEFAULT_VALUES)
    }

    override fun stop() {
        started = false
    }

    val isStarted: Boolean get() = started

    fun emit(
        signal: VehicleSignal,
        value: Double,
        status: SampleStatus = SampleStatus.AVAILABLE,
        timestampMillis: Long = clock.nowMillis(),
    ) {
        _signals.update { it + (signal to SignalSample(signal, value, timestampMillis, status)) }
    }

    fun emitAll(
        values: Map<VehicleSignal, Double> = currentValues(),
        status: SampleStatus = SampleStatus.AVAILABLE,
        timestampMillis: Long = clock.nowMillis(),
    ) {
        values.forEach { (signal, value) -> emit(signal, value, status, timestampMillis) }
    }

    /** Simulates the car dropping a property: the next sample reports UNAVAILABLE. */
    fun drop(vararg signals: VehicleSignal) {
        signals.forEach { signal ->
            val current = _signals.value[signal]?.value ?: 0.0
            emit(signal, current, SampleStatus.UNAVAILABLE)
        }
    }

    /** Simulates a stale-but-known reading. */
    fun markStale(vararg signals: VehicleSignal) {
        signals.forEach { signal ->
            val current = _signals.value[signal]?.value ?: 0.0
            emit(signal, current, SampleStatus.STALE)
        }
    }

    fun raiseError(vararg signals: VehicleSignal) {
        signals.forEach { signal ->
            val current = _signals.value[signal]?.value ?: 0.0
            emit(signal, current, SampleStatus.ERROR)
        }
    }

    fun currentValues(): Map<VehicleSignal, Double> =
        _signals.value.mapValues { it.value.value }

    fun sample(signal: VehicleSignal): SignalSample? = _signals.value[signal]

    override fun writeHvac(tempCelsius: Double): WriteResult {
        if (VehicleSignal.HvacTemp !in supported) return WriteResult.Unsupported
        emit(VehicleSignal.HvacTemp, tempCelsius)
        return WriteResult.Accepted
    }

    companion object {
        val DEFAULT_VALUES: Map<VehicleSignal, Double> = mapOf(
            VehicleSignal.Speed to 62.0,
            VehicleSignal.BatteryLevel to 68.0,
            VehicleSignal.RangeKm to 310.0,
            VehicleSignal.ChargePortOpen to 0.0,
            VehicleSignal.HvacTemp to 21.5,
        )
    }
}
