package com.autocockpit.app.car

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.autocockpit.app.clock.Clock
import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.VehicleSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Real CarPropertyManager binding. Only this class references android.car, so the rest of the app
 * (and every test) runs on a plain JVM. Construction throws when the car service is absent;
 * [CarPropertyGatewayFactory] catches that and falls back to the fake feed.
 */
class AndroidCarPropertyGateway(
    context: Context,
    private val clock: Clock,
) : CarPropertyGateway {

    private val handler = Handler(Looper.getMainLooper())
    private val car: Car = Car.createCar(context.applicationContext, handler)
        ?: throw IllegalStateException("Car.createCar returned null")
    private val manager: CarPropertyManager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        ?: throw IllegalStateException("CarPropertyManager unavailable")

    private val _signals = MutableStateFlow<Map<VehicleSignal, SignalSample>>(emptyMap())
    override val signals: StateFlow<Map<VehicleSignal, SignalSample>> = _signals.asStateFlow()

    override val info: GatewayInfo = GatewayInfo(
        available = true,
        implementation = "AndroidCarPropertyGateway",
        fallbackReason = null,
    )

    private val propertyIds: Map<VehicleSignal, Int> = linkedMapOf(
        VehicleSignal.Speed to VehiclePropertyIds.PERF_VEHICLE_SPEED,
        VehicleSignal.BatteryLevel to VehiclePropertyIds.EV_BATTERY_LEVEL,
        VehicleSignal.RangeKm to VehiclePropertyIds.RANGE_REMAINING,
        VehicleSignal.HvacTemp to VehiclePropertyIds.HVAC_TEMPERATURE_SET,
    )

    private var registered = false

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val signal = propertyIds.entries.firstOrNull { it.value == value.propertyId }?.key ?: return
            val status = when (value.status) {
                CarPropertyValue.STATUS_AVAILABLE -> SampleStatus.AVAILABLE
                CarPropertyValue.STATUS_UNAVAILABLE -> SampleStatus.UNAVAILABLE
                else -> SampleStatus.ERROR
            }
            val raw = when (val data = value.value) {
                is Float -> data.toDouble()
                is Int -> data.toDouble()
                is Boolean -> if (data) 1.0 else 0.0
                else -> 0.0
            }
            emit(signal, raw, status, value.timestamp)
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            val signal = propertyIds.entries.firstOrNull { it.value == propertyId }?.key ?: return
            emit(signal, _signals.value[signal]?.value ?: 0.0, SampleStatus.ERROR, clock.nowMillis())
        }
    }

    override fun supportedSignals(): Set<VehicleSignal> = propertyIds.keys

    override fun start() {
        if (registered) return
        propertyIds.forEach { (signal, id) ->
            runCatching {
                val initial = manager.getFloatProperty(id, 0)
                emit(signal, initial.toDouble(), SampleStatus.AVAILABLE, clock.nowMillis())
            }.onFailure {
                Log.w(TAG, "no initial value for $signal (property $id): ${it.message}")
            }
            runCatching { manager.registerCallback(callback, id, RATE_HZ) }
                .onFailure { Log.w(TAG, "registerCallback failed for $signal: ${it.message}") }
        }
        registered = true
    }

    override fun stop() {
        if (!registered) return
        runCatching { manager.unregisterCallback(callback) }
        registered = false
    }

    override fun writeHvac(tempCelsius: Double): WriteResult {
        if (VehicleSignal.HvacTemp !in propertyIds) return WriteResult.Unsupported
        return runCatching {
            manager.setProperty(
                Float::class.javaObjectType,
                VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                HVAC_ZONE_ALL,
                tempCelsius.toFloat(),
            )
            emit(VehicleSignal.HvacTemp, tempCelsius, SampleStatus.AVAILABLE, clock.nowMillis())
            WriteResult.Accepted as WriteResult
        }.getOrElse { WriteResult.Rejected(it.message ?: it.javaClass.simpleName) }
    }

    private fun emit(signal: VehicleSignal, value: Double, status: SampleStatus, timestampMillis: Long) {
        val sample = SignalSample(signal, value, timestampMillis.coerceAtLeast(0L), status)
        _signals.update { it + (signal to sample) }
    }

    companion object {
        private const val TAG = "CarPropertyGateway"
        private const val RATE_HZ = 10f

        /** HVAC_TEMPERATURE_SET global zone id. */
        private const val HVAC_ZONE_ALL = 0
    }
}
