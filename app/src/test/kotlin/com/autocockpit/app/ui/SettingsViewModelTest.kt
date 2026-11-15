package com.autocockpit.app.ui

import com.autocockpit.app.TestClock
import com.autocockpit.app.car.FakeCarPropertyGateway
import com.autocockpit.app.car.GatewayInfo
import com.autocockpit.app.link.CompanionLinkController
import com.autocockpit.app.ui.vm.SettingsViewModel
import com.autocockpit.core.TestPayloadsForApp
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import com.autocockpit.core.handoff.HandoffRepository
import com.autocockpit.core.link.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var clock: TestClock
    private lateinit var gateway: FakeCarPropertyGateway
    private lateinit var handoffRepository: HandoffRepository
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        clock = TestClock(1_000L)
        gateway = FakeCarPropertyGateway(clock, initialValues = emptyMap())
        handoffRepository = HandoffRepository()
        viewModel = SettingsViewModel(
            gateway = gateway,
            handoffRepository = handoffRepository,
            link = CompanionLinkController(),
            clock = clock,
            gatewayInfo = gateway.info,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun row(signal: VehicleSignal) = viewModel.state.value.rows.first { it.signal == signal }

    @Test
    fun freshSamplesAreLive() {
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 1_000L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(1_000L)
        assertEquals(UiTier.LIVE, row(VehicleSignal.Speed).tier)
        assertEquals("62", row(VehicleSignal.Speed).displayValue)
        assertEquals("km/h", row(VehicleSignal.Speed).unit)
        assertEquals(UiTier.LIVE, row(VehicleSignal.BatteryLevel).tier)
    }

    @Test
    fun propertyDropoutWalksLastKnownThenUnavailableThenRecovers() {
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 1_000L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(1_000L)
        assertEquals(UiTier.LIVE, row(VehicleSignal.Speed).tier)

        // 4s without an update: past the 3s stale window.
        viewModel.refresh(5_000L)
        assertEquals(UiTier.LAST_KNOWN, row(VehicleSignal.Speed).tier)
        assertTrue(row(VehicleSignal.Speed).ageSeconds >= 4)
        assertEquals("62", row(VehicleSignal.Speed).displayValue)

        // 14s without an update: past the 12s fallback window.
        viewModel.refresh(15_000L)
        assertEquals(UiTier.UNAVAILABLE, row(VehicleSignal.Speed).tier)
        assertEquals("—", row(VehicleSignal.Speed).displayValue)

        // Data returns.
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 15_000L)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(15_100L)
        assertEquals(UiTier.LIVE, row(VehicleSignal.Speed).tier)
        assertEquals("62", row(VehicleSignal.Speed).displayValue)
    }

    @Test
    fun explicitUnavailableSampleDegradesImmediately() {
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 1_000L)
        dispatcher.scheduler.runCurrent()
        gateway.drop(VehicleSignal.BatteryLevel)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(1_100L)
        assertEquals(UiTier.UNAVAILABLE, row(VehicleSignal.BatteryLevel).tier)
        assertEquals("—", row(VehicleSignal.BatteryLevel).displayValue)
    }

    @Test
    fun staleSamplesShowLastKnown() {
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 1_000L)
        dispatcher.scheduler.runCurrent()
        gateway.markStale(VehicleSignal.RangeKm)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(1_100L)
        assertEquals(UiTier.LAST_KNOWN, row(VehicleSignal.RangeKm).tier)
    }

    @Test
    fun errorBurstForcesUnavailable() {
        gateway.emitAll(FakeCarPropertyGateway.DEFAULT_VALUES, timestampMillis = 1_000L)
        dispatcher.scheduler.runCurrent()
        repeat(3) {
            clock.advance(100L)
            gateway.raiseError(VehicleSignal.Speed)
            dispatcher.scheduler.runCurrent()
        }
        viewModel.refresh(clock.nowMillis())
        assertEquals(UiTier.UNAVAILABLE, row(VehicleSignal.Speed).tier)
    }

    @Test
    fun hvacWriteUpdatesTargetAndSignal() {
        val error = viewModel.writeHvac(24.0f)
        assertNull(error)
        dispatcher.scheduler.runCurrent()
        viewModel.refresh(clock.nowMillis())
        assertEquals(24.0f, viewModel.state.value.hvacTarget, 0.001f)
        assertEquals("24.0", row(VehicleSignal.HvacTemp).displayValue)
        assertEquals(UiTier.LIVE, row(VehicleSignal.HvacTemp).tier)
    }

    @Test
    fun hvacWriteRejectsOutOfRange() {
        val error = viewModel.writeHvac(40f)
        assertNotNull(error)
        assertTrue(error!!.contains("outside"))
    }

    @Test
    fun resyncWithoutPayloadDegradesWithReason() {
        viewModel.resync()
        val link = viewModel.state.value.link
        assertEquals(LinkState.DEGRADED, link.state)
        assertNotNull(link.lastError)
        assertTrue(link.lastError!!.contains("no handoff payload"))
        assertEquals(1, link.attempt)
    }

    @Test
    fun resyncWithPayloadReachesSyncedAndShowsDriver() {
        handoffRepository.commit(TestPayloadsForApp.handoff(9))
        viewModel.resync()
        val state = viewModel.state.value
        assertEquals(LinkState.SYNCED, state.link.state)
        assertEquals(0, state.link.attempt)
        assertEquals("Companion Driver", state.handoff.driverName)
        assertEquals(9, state.handoff.trackCount)
        // One commit plus the re-sync re-application.
        assertEquals(2, state.handoff.revision)
    }

    @Test
    fun handoffCommitRefreshesDriverAndHvacTarget() {
        assertNull(viewModel.state.value.handoff.driverName)
        handoffRepository.commit(TestPayloadsForApp.handoff(4))
        val state = viewModel.state.value
        assertEquals("Companion Driver", state.handoff.driverName)
        assertEquals("DRIVER", state.handoff.seat)
        assertEquals(4, state.handoff.trackCount)
        assertEquals(23.0f, state.hvacTarget, 0.001f)
    }

    @Test
    fun linkDropIsVisibleInTheUi() {
        handoffRepository.commit(TestPayloadsForApp.handoff(2))
        viewModel.resync()
        assertEquals(LinkState.SYNCED, viewModel.state.value.link.state)

        viewModel.attemptReconnect() // ReconnectTick while SYNCED is illegal, state must not move
        assertEquals(LinkState.SYNCED, viewModel.state.value.link.state)

        val link = CompanionLinkController()
        val vm = SettingsViewModel(gateway, handoffRepository, link, clock, gateway.info)
        assertTrue(link.resync { true }.state == LinkState.SYNCED)
        link.reportLinkLost()
        vm.refresh()
        assertEquals(LinkState.DEGRADED, vm.state.value.link.state)
        assertEquals(1, vm.state.value.link.attempt)
        assertFalse(vm.state.value.link.exhausted)
    }

    @Test
    fun gatewayFallbackBannerIsExposed() {
        val fallback = GatewayInfo(
            available = false,
            implementation = "FakeCarPropertyGateway",
            fallbackReason = "Not an automotive device",
        )
        val vm = SettingsViewModel(gateway, handoffRepository, CompanionLinkController(), clock, fallback)
        assertTrue(vm.state.value.gateway.isFallback)
        assertEquals("Not an automotive device", vm.state.value.gateway.fallbackReason)
    }
}
