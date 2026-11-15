package com.autocockpit.core.simulation

import com.autocockpit.core.codec.Chunker
import com.autocockpit.core.codec.HandoffCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationHarnessTest {

    private val smallConfig = SimulationConfig(
        sessionsPerLossRate = 12,
        seed = 7L,
        maxTracks = 40,
        simulatedFramesPerSession = 30,
    )

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun sameSeedProducesIdenticalReports() {
        val first = json.encodeToString(SimulationHarness(smallConfig).run())
        val second = json.encodeToString(SimulationHarness(smallConfig).run())
        assertEquals(first, second)
    }

    @Test
    fun differentSeedsProduceDifferentReports() {
        val a = json.encodeToString(SimulationHarness(smallConfig).run())
        val b = json.encodeToString(SimulationHarness(smallConfig.copy(seed = 8L)).run())
        assertTrue(a != b)
    }

    @Test
    fun reportCountsEveryConfiguredSession() {
        val report = SimulationHarness(smallConfig).run()
        assertEquals(smallConfig.sessionsPerLossRate * smallConfig.lossRates.size, report.config.totalSessions)
        assertEquals(report.config.totalSessions, report.handoff.sessions)
        assertEquals(smallConfig.lossRates.size, report.handoff.byLossRate.size)
        assertEquals(smallConfig.lossRates, report.handoff.byLossRate.map { it.lossRate })
    }

    @Test
    fun everyHandoffSucceedsInTheDefaultModel() {
        val report = SimulationHarness(smallConfig).run()
        assertEquals(100.0, report.handoff.successRate, 0.0001)
        for (rate in report.handoff.byLossRate) {
            assertEquals("loss ${rate.lossRate}", 100.0, rate.successRate, 0.0001)
        }
    }

    @Test
    fun faultsAreActuallyInjected() {
        val report = SimulationHarness(smallConfig).run()
        assertTrue("expected property dropouts", report.faults.propertyDropouts > 0)
        assertTrue("expected stale samples", report.faults.staleSamples > 0)
        assertTrue("expected chunk losses", report.faults.chunkLosses > 0)
        assertTrue("expected crc corruptions", report.faults.crcCorruptions > 0)
        assertTrue("expected ble disconnects", report.faults.bleDisconnects > 0)
    }

    @Test
    fun lossRateIncreasesRetransmits() {
        val report = SimulationHarness(smallConfig).run()
        val none = report.handoff.byLossRate.first { it.lossRate == 0.0 }
        val heavy = report.handoff.byLossRate.first { it.lossRate == 0.10 }
        // Even at 0% chunk loss the modelled radio corrupts ~1% of chunks, so some retransmit
        // happens; 10% loss must be strictly worse.
        assertTrue("heavy loss should retransmit more", heavy.retransmits.mean > none.retransmits.mean)
        assertTrue(heavy.retransmits.mean > 0.0)
        assertEquals(100.0, heavy.successRate, 0.0001)
    }

    @Test
    fun degradationCountersCoverEverySignalAndFrame() {
        val report = SimulationHarness(smallConfig).run()
        val frames = report.degradation.framesObserved
        assertEquals(frames, report.degradation.liveFrames + report.degradation.lastKnownFrames + report.degradation.unavailableFrames)
        assertTrue(frames > 0)
        assertTrue(report.degradation.livePct > 50.0)
        assertTrue(report.degradation.lastKnownFrames > 0)
        assertTrue(report.degradation.unavailableFrames > 0)
    }

    @Test
    fun payloadSectionMatchesRealEncodings() {
        val report = SimulationHarness(smallConfig).run()
        val fiveHundred = HandoffCodec.encode(SyntheticPayloads.canonical(500))
        assertEquals(fiveHundred.size, report.payload.fiveHundredTrackBytes)
        assertEquals(Chunker(20).split(fiveHundred).size, report.payload.fiveHundredTrackChunksAt20)
        assertEquals(
            fiveHundred.size + report.payload.fiveHundredTrackChunksAt20 *
                (Chunker.CHUNK_HEADER_BYTES + com.autocockpit.core.handoff.HandoffProtocol.ATT_HEADER_BYTES),
            report.handoff.bytesOnWire500Tracks,
        )
        assertEquals(500, report.handoff.maxPayloadTracks)
        assertTrue(report.handoff.maxPayloadBytes > 0)
        assertTrue(report.handoff.maxPayloadBytes >= fiveHundred.size)
    }

    @Test
    fun payloadSizesGrowWithTrackCount() {
        val report = SimulationHarness(smallConfig).run()
        assertTrue(report.payload.oneTrackBytes < report.payload.tenTrackBytes)
        assertTrue(report.payload.tenTrackBytes < report.payload.hundredTrackBytes)
        assertTrue(report.payload.hundredTrackBytes < report.payload.fiveHundredTrackBytes)
    }

    @Test
    fun invalidConfigurationIsRejected() {
        assertTrue(runCatching { SimulationConfig(sessionsPerLossRate = 0) }.isFailure)
        assertTrue(runCatching { SimulationConfig(lossRates = listOf(1.5)) }.isFailure)
        assertTrue(runCatching { SimulationConfig(mtu = 4) }.isFailure)
    }

    @Test
    fun recoveryLatencyIsReportedWhenDisconnectsHappen() {
        val report = SimulationHarness(smallConfig).run()
        assertTrue(report.sessionsWithDisconnectReport())
        assertTrue(report.handoff.meanRecoveryLatencyMs.count > 0)
        assertTrue(report.handoff.meanRecoveryLatencyMs.mean >= 0.0)
        assertTrue(report.handoff.meanReconnectAttempts.mean >= 1.0)
    }

    @Test
    fun trafficAccountingExceedsFrameSizeAtSmallMtu() {
        val report = SimulationHarness(smallConfig).run()
        val frame = report.payload.fiveHundredTrackBytes
        val chunks = report.payload.fiveHundredTrackChunksAt20
        // Every chunk adds a 4-byte protocol header and a 3-byte ATT header.
        assertEquals(frame + chunks * 7, report.handoff.bytesOnWire500Tracks)
        assertTrue(chunks > frame / 20)
    }

    private fun SimulationReport.sessionsWithDisconnectReport(): Boolean = handoff.sessionsWithReconnect > 0
}
