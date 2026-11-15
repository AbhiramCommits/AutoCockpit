package android.car

import android.content.Context
import android.os.Handler

/**
 * Compile-time stub used only when the Android SDK's optional `android.car.jar` is absent.
 * Signatures mirror the platform API surface that `AndroidCarPropertyGateway` calls.
 */
abstract class Car {
    abstract fun <T> getCarManager(serviceName: String): T

    companion object {
        const val PROPERTY_SERVICE: String = "property"

        @JvmStatic
        fun createCar(context: Context, handler: Handler): Car? = null
    }
}
