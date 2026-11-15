package com.autocockpit.app.ui

import com.autocockpit.app.TestClock
import com.autocockpit.app.car.FakeCarPropertyGateway
import com.autocockpit.app.ui.vm.GaugeViewModel
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GaugeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: TestClock
    private lateinit var gateway: FakeCarPropertyGateway
    private lateinit var viewModel: GaugeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = TestClock(0L)
        gateway = FakeCarPropertyGateway(clock, initialValues = emptyMap())
        viewModel = GaugeViewModel(gateway, clock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun speedIsNormalisedToTheGaugeRange() {
        gateway.emit(VehicleSignal.Speed, 90.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(0L)
        assertEquals(0.5f, viewModel.state.value.speedNormalized, 0.02f)
        assertEquals(90.0, viewModel.state.value.speedKmh, 0.001)
        assertEquals(UiTier.LIVE, viewModel.state.value.speedTier)
        assertEquals("LIVE", viewModel.state.value.label)
    }

    @Test
    fun batteryAndRangeNormalise() {
        gateway.emit(VehicleSignal.BatteryLevel, 50.0, timestampMillis = 0L)
        gateway.emit(VehicleSignal.RangeKm, 300.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(0L)
        assertEquals(0.5f, viewModel.state.value.batteryNormalized, 0.02f)
        assertEquals(0.5f, viewModel.state.value.rangeNormalized, 0.02f)
    }

    @Test
    fun staleSpeedDesaturatesTheGauge() {
        gateway.emit(VehicleSignal.Speed, 100.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(0L)
        assertTrue(!viewModel.state.value.degraded)

        viewModel.refresh(3_000L)
        assertEquals(UiTier.LAST_KNOWN, viewModel.state.value.speedTier)
        assertTrue(viewModel.state.value.degraded)
        assertTrue(viewModel.state.value.label.startsWith("LAST KNOWN"))
    }

    @Test
    fun unavailableSpeedIsLabelled() {
        gateway.emit(VehicleSignal.Speed, 100.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(60_000L)
        assertEquals(UiTier.UNAVAILABLE, viewModel.state.value.speedTier)
        assertTrue(viewModel.state.value.degraded)
        assertEquals("UNAVAILABLE", viewModel.state.value.label)
    }

    @Test
    fun smoothingApproachesTheTarget() {
        gateway.emit(VehicleSignal.Speed, 180.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(0L)
        // First refresh initialises directly at 1.0.
        assertEquals(1.0f, viewModel.state.value.speedNormalized, 0.001f)
        gateway.emit(VehicleSignal.Speed, 0.0, timestampMillis = 0L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(10L)
        val first = viewModel.state.value.speedNormalized
        assertTrue("value should fall smoothly: $first", first in 0.5f..0.999f)
        repeat(20) { viewModel.refresh(10L) }
        assertTrue(viewModel.state.value.speedNormalized < 0.2f)
    }
}
