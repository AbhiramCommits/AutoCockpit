package com.autocockpit.core.health

import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertyHealthMonitorTest {

    private val policy = DegradationPolicy(
        staleAfterMillis = 1_000,
        unavailableAfterMillis = 5_000,
        errorBurstThreshold = 3,
        errorBurstWindowMillis = 2_000,
    )

    private fun sample(value: Double, at: Long, status: SampleStatus = SampleStatus.AVAILABLE, signal: VehicleSignal = VehicleSignal.Speed) =
        SignalSample(signal, value, at, status)

    @Test
    fun freshSampleIsLive() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(88.0, 10_000))
        val view = monitor.view(VehicleSignal.Speed, 10_100)
        assertEquals(UiTier.LIVE, view.tier)
        assertEquals(88.0, view.value!!, 0.0)
        assertEquals(100, view.ageMillis)
        assertNull(view.reason)
    }

    @Test
    fun samplePastStaleWindowIsLastKnown() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(88.0, 10_000))
        val view = monitor.view(VehicleSignal.Speed, 11_500)
        assertEquals(UiTier.LAST_KNOWN, view.tier)
        assertEquals(1_500, view.ageMillis)
        assertTrue(view.reason!!.contains("no update"))
    }

    @Test
    fun samplePastUnavailableWindowIsUnavailableWithFallbackValue() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(88.0, 10_000))
        val view = monitor.view(VehicleSignal.Speed, 20_000)
        assertEquals(UiTier.UNAVAILABLE, view.tier)
        assertEquals(88.0, view.value!!, 0.0)
        assertEquals(10_000, view.ageMillis)
    }

    @Test
    fun unavailableStatusDegradesImmediately() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(88.0, 10_000))
        monitor.onSample(sample(88.0, 10_100, SampleStatus.UNAVAILABLE))
        val view = monitor.view(VehicleSignal.Speed, 10_100)
        assertEquals(UiTier.UNAVAILABLE, view.tier)
        assertEquals("reported unavailable", view.reason)
    }

    @Test
    fun staleStatusIsLastKnown() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(55.0, 10_000))
        monitor.onSample(sample(55.0, 10_200, SampleStatus.STALE))
        val view = monitor.view(VehicleSignal.Speed, 10_200)
        assertEquals(UiTier.LAST_KNOWN, view.tier)
        assertEquals(SampleStatus.STALE, view.lastStatus)
    }

    @Test
    fun errorBurstForcesUnavailable() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(42.0, 10_000))
        monitor.onSample(sample(42.0, 10_100, SampleStatus.ERROR))
        monitor.onSample(sample(42.0, 10_200, SampleStatus.ERROR))
        assertEquals(UiTier.LAST_KNOWN, monitor.view(VehicleSignal.Speed, 10_200).tier)
        monitor.onSample(sample(42.0, 10_300, SampleStatus.ERROR))
        val view = monitor.view(VehicleSignal.Speed, 10_300)
        assertEquals(UiTier.UNAVAILABLE, view.tier)
        assertTrue(view.reason!!.contains("error burst"))
    }

    @Test
    fun singleErrorIsLastKnownNotUnavailable() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(42.0, 10_000))
        monitor.onSample(sample(42.0, 10_100, SampleStatus.ERROR))
        assertEquals(UiTier.LAST_KNOWN, monitor.view(VehicleSignal.Speed, 10_100).tier)
    }

    @Test
    fun freshSampleRecoversFromUnavailable() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(42.0, 10_000))
        monitor.onSample(sample(42.0, 10_100, SampleStatus.UNAVAILABLE))
        assertEquals(UiTier.UNAVAILABLE, monitor.view(VehicleSignal.Speed, 10_200).tier)
        monitor.onSample(sample(51.0, 10_300))
        val view = monitor.view(VehicleSignal.Speed, 10_350)
        assertEquals(UiTier.LIVE, view.tier)
        assertEquals(51.0, view.value!!, 0.0)
    }

    @Test
    fun recoveryFromErrorBurstClearsTimestamps() {
        val monitor = PropertyHealthMonitor(policy)
        repeat(3) { monitor.onSample(sample(10.0, 10_000L + it * 100, SampleStatus.ERROR)) }
        assertEquals(UiTier.UNAVAILABLE, monitor.view(VehicleSignal.Speed, 10_300).tier)
        monitor.onSample(sample(64.0, 10_400))
        assertEquals(UiTier.LIVE, monitor.view(VehicleSignal.Speed, 10_500).tier)
    }

    @Test
    fun missingSignalIsUnavailableWithoutValue() {
        val monitor = PropertyHealthMonitor(policy)
        val view = monitor.view(VehicleSignal.HvacTemp, 1_000)
        assertEquals(UiTier.UNAVAILABLE, view.tier)
        assertNull(view.value)
        assertEquals(-1L, view.ageMillis)
    }

    @Test
    fun signalsAreIndependent() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(30.0, 10_000, signal = VehicleSignal.Speed))
        monitor.onSample(sample(74.0, 10_000, signal = VehicleSignal.BatteryLevel))
        assertEquals(UiTier.LIVE, monitor.view(VehicleSignal.Speed, 10_100).tier)
        assertEquals(74.0, monitor.view(VehicleSignal.BatteryLevel, 10_100).value!!, 0.0)
        assertEquals(UiTier.UNAVAILABLE, monitor.view(VehicleSignal.RangeKm, 10_100).tier)
    }

    @Test
    fun outOfOrderOlderSampleDoesNotRewindValue() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(60.0, 10_000))
        monitor.onSample(sample(20.0, 9_000))
        assertEquals(60.0, monitor.view(VehicleSignal.Speed, 10_100).value!!, 0.0)
    }

    @Test
    fun observeFrameCountsTiers() {
        val monitor = PropertyHealthMonitor(policy)
        for (signal in VehicleSignal.all) {
            monitor.onSample(sample(50.0, 10_000, signal = signal))
        }
        monitor.observeFrame(10_100)
        val counters = monitor.counters()
        assertEquals(5, counters.framesObserved)
        assertEquals(5, counters.liveFrames)
        assertEquals(0, counters.unavailableFrames)
        assertEquals(100.0, counters.livePct, 0.001)
    }

    @Test
    fun observeFrameCountsUnavailableSignals() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.observeFrame(1_000)
        val counters = monitor.counters()
        assertEquals(5, counters.unavailableFrames)
        assertEquals(100.0, counters.unavailablePct, 0.001)
    }

    @Test
    fun resetCountersLeavesSamplesIntact() {
        val monitor = PropertyHealthMonitor(policy)
        monitor.onSample(sample(12.0, 10_000))
        monitor.observeFrame(10_100)
        monitor.resetCounters()
        assertEquals(0, monitor.counters().framesObserved)
        assertEquals(UiTier.LIVE, monitor.view(VehicleSignal.Speed, 10_100).tier)
    }

    @Test
    fun invalidPolicyIsRejected() {
        assertTrue(runCatching { DegradationPolicy(staleAfterMillis = 0) }.isFailure)
        assertTrue(runCatching { DegradationPolicy(staleAfterMillis = 5_000, unavailableAfterMillis = 1_000) }.isFailure)
        assertTrue(runCatching { DegradationPolicy(errorBurstThreshold = 0) }.isFailure)
    }

    @Test
    fun signalScaleNormalisesInRange() {
        assertEquals(0.5, SignalScale.normalize(VehicleSignal.Speed, 90.0), 1e-9)
        assertEquals(0.0, SignalScale.normalize(VehicleSignal.BatteryLevel, -10.0), 0.0)
        assertEquals(1.0, SignalScale.normalize(VehicleSignal.RangeKm, 9_999.0), 0.0)
        assertEquals(1.0, SignalScale.normalize(VehicleSignal.ChargePortOpen, 1.0), 0.0)
        assertEquals(0.5, SignalScale.normalize(VehicleSignal.HvacTemp, 22.0), 1e-9)
        assertEquals(22.0, SignalScale.denormalize(VehicleSignal.HvacTemp, 0.5), 1e-9)
    }
}
