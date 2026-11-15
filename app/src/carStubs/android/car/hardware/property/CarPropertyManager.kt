package android.car.hardware.property

import android.car.hardware.CarPropertyValue

/** Compile-time stub for CarPropertyManager. */
class CarPropertyManager {
    interface CarPropertyEventCallback {
        fun onChangeEvent(value: CarPropertyValue<*>)
        fun onErrorEvent(propertyId: Int, zone: Int)
    }

    fun getFloatProperty(propertyId: Int, areaId: Int): Float = 0f

    fun registerCallback(
        callback: CarPropertyEventCallback,
        propertyId: Int,
        rateHz: Float,
    ) = Unit

    fun unregisterCallback(callback: CarPropertyEventCallback) = Unit

    fun setProperty(
        valueClass: Class<*>,
        propertyId: Int,
        areaId: Int,
        value: Any,
    ): Boolean = false
}
