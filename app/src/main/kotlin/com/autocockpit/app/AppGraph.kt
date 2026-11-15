package com.autocockpit.app

import android.content.Context
import com.autocockpit.app.car.CarPropertyGateway
import com.autocockpit.app.car.CarPropertyGatewayFactory
import com.autocockpit.app.car.GatewayInfo
import com.autocockpit.app.clock.AndroidClock
import com.autocockpit.app.clock.Clock
import com.autocockpit.app.link.CompanionLinkController
import com.autocockpit.app.media.Catalog
import com.autocockpit.app.media.MediaCatalogRepository
import com.autocockpit.core.handoff.HandoffRepository

/**
 * Manual dependency graph. Kept deliberately small: the gateway and transport abstractions are
 * the boundaries that make the app headless-testable, so no annotation processor is needed.
 */
class AppGraph private constructor(context: Context) {
    val clock: Clock = AndroidClock
    val catalogRepository: MediaCatalogRepository = MediaCatalogRepository(context.assets)
    val catalog: Catalog by lazy { catalogRepository.load() }
    val handoffRepository: HandoffRepository = HandoffRepository()
    val linkController: CompanionLinkController = CompanionLinkController()

    private val gatewaySelection: CarPropertyGatewayFactory.Selection =
        CarPropertyGatewayFactory.create(context, clock)

    val gateway: CarPropertyGateway get() = gatewaySelection.gateway
    val gatewayFallbackReason: String? get() = gatewaySelection.fallbackReason

    /** Gateway description with the factory's fallback banner folded in. */
    val gatewayInfo: GatewayInfo get() = gateway.info.copy(
        fallbackReason = gatewayFallbackReason ?: gateway.info.fallbackReason,
    )

    companion object {
        @Volatile
        private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) {
                instance ?: AppGraph(context.applicationContext).also { instance = it }
            }

        /** Drops the singleton between Robolectric tests so state cannot leak. */
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
