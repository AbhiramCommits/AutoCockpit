package com.autocockpit.app.car

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.autocockpit.app.clock.Clock

/**
 * Chooses the real AAOS gateway when the car service is reachable, otherwise the fake feed.
 * Never throws: a missing car service, missing android.car library or missing automotive feature
 * all produce a visible fallback banner instead of a crash.
 */
object CarPropertyGatewayFactory {

    data class Selection(
        val gateway: CarPropertyGateway,
        val fallbackReason: String?,
    )

    fun create(context: Context, clock: Clock): Selection {
        val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        if (!automotive) {
            return Selection(
                FakeCarPropertyGateway(clock),
                "Not an automotive device: using the deterministic property feed.",
            )
        }
        if (runCatching { Class.forName("android.car.Car") }.isFailure) {
            return Selection(
                FakeCarPropertyGateway(clock),
                "android.car is not available on this image: using the deterministic property feed.",
            )
        }
        return try {
            val gateway = AndroidCarPropertyGateway(context, clock)
            gateway.start()
            Selection(gateway, null)
        } catch (t: Throwable) {
            Log.w("CarPropertyGatewayFactory", "car gateway unavailable, falling back", t)
            Selection(
                FakeCarPropertyGateway(clock),
                "Car service unavailable (${t.javaClass.simpleName}): using the deterministic property feed.",
            )
        }
    }
}
