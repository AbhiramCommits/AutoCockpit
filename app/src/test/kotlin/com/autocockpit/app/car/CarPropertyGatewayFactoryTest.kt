package com.autocockpit.app.car

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autocockpit.app.clock.AndroidClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CarPropertyGatewayFactoryTest {

    @Test
    fun nonAutomotiveDeviceFallsBackToTheFakeFeed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selection = CarPropertyGatewayFactory.create(context, AndroidClock)
        assertTrue(selection.gateway is FakeCarPropertyGateway)
        assertNotNull(selection.fallbackReason)
        assertTrue(selection.fallbackReason!!.contains("Not an automotive device"))
        assertEquals("FakeCarPropertyGateway", selection.gateway.info.implementation)
    }

    @Test
    fun fallbackGatewaySupportsHvacWrites() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = CarPropertyGatewayFactory.create(context, AndroidClock).gateway
        assertEquals(WriteResult.Accepted, gateway.writeHvac(22.0))
        assertEquals(22.0, gateway.signals.value.getValue(com.autocockpit.core.domain.VehicleSignal.HvacTemp).value, 0.001)
    }

    @Test
    fun fakeGatewayReportsEverySignalAsAvailableInitially() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = FakeCarPropertyGateway(AndroidClock)
        assertEquals(5, gateway.supportedSignals().size)
        assertEquals(5, gateway.currentValues().size)
    }
}
