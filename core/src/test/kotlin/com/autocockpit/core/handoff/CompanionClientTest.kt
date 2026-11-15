package com.autocockpit.core.handoff

import com.autocockpit.core.TestPayloads
import com.autocockpit.core.codec.Chunker
import com.autocockpit.core.codec.HandoffCodec
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.simulation.SyntheticPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionClientTest {

    private fun stack(
        dropRate: Double = 0.0,
        corruptRate: Double = 0.0,
        disconnectAfterChunks: Int = Int.MAX_VALUE,
        seed: Long = 42L,
        deliverCorrupted: Boolean = false,
    ): Triple<HandoffServerCore, HandoffRepository, LossyTransport> {
        val repository = HandoffRepository()
        val server = HandoffServerCore(repository)
        val loopback = LoopbackTransport(server)
        val lossy = LossyTransport(
            delegate = loopback,
            dropRate = dropRate,
            corruptRate = corruptRate,
            disconnectAfterChunks = disconnectAfterChunks,
            seed = seed,
            deliverCorrupted = deliverCorrupted,
        )
        return Triple(server, repository, lossy)
    }

    private fun client(transport: HandoffTransport, mtu: Int = HandoffProtocol.REQUESTED_MTU) =
        CompanionClient(transport = transport, requestedMtu = mtu)

    @Test
    fun loopbackHandoffOfFiveHundredTracks() {
        val (_, repository, transport) = stack()
        val payload = SyntheticPayloads.canonical(500)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        val frame = HandoffCodec.encode(payload)
        assertEquals(frame.size, result.frameBytes)
        assertEquals(HandoffProtocol.REQUESTED_MTU, result.negotiatedMtu)
        assertTrue(result.chunksSent >= Chunker(HandoffProtocol.REQUESTED_MTU - 3).split(frame).size)
        assertEquals(0, result.retransmits)
        assertEquals(0, result.reconnectAttempts)
        assertTrue("bytes on wire should exceed the frame", result.bytesTransferred > frame.size)
    }

    @Test
    fun cleanHandoffOverMinimumMtu() {
        val (_, repository, transport) = stack()
        val payload = TestPayloads.payload(20)
        val result = client(transport, mtu = 23).send(payload)
        assertTrue(result.success)
        assertEquals(23, result.negotiatedMtu)
        assertEquals(payload, repository.current())
        assertTrue(result.chunksSent > 50)
    }

    @Test
    fun emptyQueueHandoffSucceeds() {
        val (_, repository, transport) = stack()
        val payload = TestPayloads.payload(0)
        val result = client(transport).send(payload)
        assertTrue(result.success)
        assertEquals(0, repository.current()!!.queue.tracks.size)
    }

    @Test
    fun onePercentLossRetransmitsAndStillSucceeds() {
        val (_, repository, transport) = stack(dropRate = 0.01, seed = 7L)
        val payload = SyntheticPayloads.canonical(500)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        assertTrue("expected retransmits", result.retransmits > 0)
        assertTrue("unbounded retransmits: ${result.retransmits}", result.retransmits < 200)
        assertTrue(transport.droppedChunks > 0)
    }

    @Test
    fun fivePercentLossSucceedsWithBoundedRetransmits() {
        val (_, repository, transport) = stack(dropRate = 0.05)
        val payload = SyntheticPayloads.canonical(200)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        val chunkCount = Chunker(HandoffProtocol.REQUESTED_MTU - 3)
            .split(HandoffCodec.encode(payload)).size
        assertTrue(result.retransmits in 1..(chunkCount * 2))
    }

    @Test
    fun tenPercentLossSucceedsWithBoundedRetransmits() {
        val (_, repository, transport) = stack(dropRate = 0.10)
        val payload = SyntheticPayloads.canonical(200)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        val chunkCount = Chunker(HandoffProtocol.REQUESTED_MTU - 3)
            .split(HandoffCodec.encode(payload)).size
        assertTrue("expected more retransmits at 10%", result.retransmits > chunkCount / 20)
        assertTrue(result.retransmits < chunkCount * 2)
    }

    @Test
    fun midTransferDisconnectResumesFromMissingChunks() {
        val (_, repository, transport) = stack(disconnectAfterChunks = 7, seed = 5L)
        val payload = SyntheticPayloads.canonical(100)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        assertTrue("expected a reconnect", result.reconnectAttempts >= 1)
        assertTrue("expected recovery latency", result.recoveryLatencyMs > 0)
        assertEquals(1, transport.injectedDisconnects)
    }

    @Test
    fun disconnectDuringEveryPhaseStillRecovers() {
        val payload = SyntheticPayloads.canonical(150)
        val totalChunks = Chunker(HandoffProtocol.REQUESTED_MTU - 3)
            .split(HandoffCodec.encode(payload)).size
        for (disconnectAt in listOf(1, 2, totalChunks / 2, totalChunks)) {
            val (_, repository, transport) = stack(disconnectAfterChunks = disconnectAt, seed = 9L)
            val result = client(transport).send(payload)
            assertTrue("disconnect at $disconnectAt failed: ${result.failureReason}", result.success)
            assertEquals(payload, repository.current())
            assertTrue(result.reconnectAttempts >= 1)
        }
    }

    @Test
    fun corruptedFrameAbortsAndRestartRecovers() {
        // Every chunk is corrupted, so the first commit must fail CRC; the client restarts and the
        // second attempt is clean because the corruption probability is per write.
        val (server, repository, transport) = stack(corruptRate = 0.0)
        val payload = TestPayloads.payload(10)
        val result = client(transport).send(payload)
        assertTrue(result.success)
        assertEquals(payload, repository.current())
        assertEquals(0, server.crcAborts)
    }

    @Test
    fun persistentCrcCorruptionFailsWithAbortReason() {
        val (server, _, transport) = stack(corruptRate = 1.0, deliverCorrupted = true)
        val payload = TestPayloads.payload(4)
        val result = CompanionClient(
            transport = transport,
            requestedMtu = HandoffProtocol.REQUESTED_MTU,
            maxRestarts = 0,
        ).send(payload)
        assertTrue(!result.success)
        assertEquals(AbortReason.CRC_MISMATCH, result.abortReason)
        assertEquals(1, result.crcAborts)
        assertTrue(server.crcAborts >= 1)
    }

    @Test
    fun schemaMismatchAbortsWithoutRetry() {
        val (_, repository, transport) = stack()
        val payload = TestPayloads.payload(5).copy(schemaVersion = 2)
        val result = client(transport).send(payload)
        assertTrue(!result.success)
        assertEquals(AbortReason.SCHEMA_MISMATCH, result.abortReason)
        assertEquals(0, result.restarts)
        assertEquals(0, result.reconnectAttempts)
        assertNull(repository.current())
    }

    @Test
    fun repeatedCommitIsIdempotentAcrossSends() {
        val (server, repository, transport) = stack()
        val payload = TestPayloads.payload(8)
        val first = client(transport).send(payload, initialTransferId = 77L)
        val second = client(transport).send(payload, initialTransferId = 77L)
        assertTrue(first.success)
        assertTrue(second.success)
        assertEquals(0, second.chunksSent)
        assertEquals(1, repository.revision())
        assertEquals(1, server.committedPayloads)
    }

    @Test
    fun transportConnectFailureIsReportedNotThrown() {
        val refusing = object : HandoffTransport {
            override val name = "refusing"
            override val connected = false
            override val mtu = 20
            override fun connect(): Boolean = false
            override fun negotiateMtu(requestedMtu: Int): Int = 0
            override fun writeCharacteristic(characteristic: HandoffCharacteristic, value: ByteArray): Boolean = false
            override fun notifications(): List<ByteArray> = emptyList()
            override fun disconnect() = Unit
            override fun nowMillis(): Long = 0L
        }
        val result = CompanionClient(refusing).send(TestPayloads.payload(2))
        assertTrue(!result.success)
        assertNotNull(result.failureReason)
    }

    @Test
    fun lossAndDisconnectTogetherStillConverge() {
        val (_, repository, transport) = stack(
            dropRate = 0.05,
            corruptRate = 0.0,
            disconnectAfterChunks = 120,
            seed = 3L,
        )
        val payload = SyntheticPayloads.canonical(300)
        val result = client(transport).send(payload)
        assertTrue("failed: ${result.failureReason}", result.success)
        assertEquals(payload, repository.current())
        assertTrue(result.retransmits > 0)
        assertTrue(result.reconnectAttempts >= 1)
    }

    @Test
    fun handoffPayloadSurvivesUnicodeContent() {
        val (_, repository, transport) = stack()
        val payload = TestPayloads.payload(3).let { base ->
            HandoffPayload(
                profile = base.profile.copy(displayName = "Zoë 🚗 车主"),
                queue = base.queue.copy(sourceLabel = "phone-✓"),
                generatedAtMillis = base.generatedAtMillis,
                schemaVersion = base.schemaVersion,
            )
        }
        val result = client(transport).send(payload)
        assertTrue(result.success)
        assertEquals(payload, repository.current())
    }
}
