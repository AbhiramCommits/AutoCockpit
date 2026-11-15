package android.car.hardware

/** Compile-time stub for CarPropertyValue. */
open class CarPropertyValue<T>(
    val propertyId: Int = 0,
    val areaId: Int = 0,
) {
    open val value: T? = null
    open val status: Int = STATUS_UNAVAILABLE
    open val timestamp: Long = 0L

    companion object {
        const val STATUS_AVAILABLE: Int = 0
        const val STATUS_UNAVAILABLE: Int = 1
        const val STATUS_ERROR: Int = 2
    }
}
