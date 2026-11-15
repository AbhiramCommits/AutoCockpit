package com.autocockpit.core.handoff

import com.autocockpit.core.TestPayloads
import com.autocockpit.core.codec.Chunker
import com.autocockpit.core.codec.HandoffCodec
import com.autocockpit.core.domain.HandoffPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffServerCoreTest {

    private lateinit var repository: HandoffRepository
    private lateinit var server: HandoffServerCore

    private fun newServer(): HandoffServerCore {
        repository = HandoffRepository()
        server = HandoffServerCore(repository)
        return server
    }

    private fun begin(transferId: Long, payload: HandoffPayload, totalChunks: Int) =
        server.onControl(
            HandoffProtocol.encodeBegin(transferId, payload.schemaVersion, HandoffCodec.encode(payload).size, totalChunks),
        )

    private fun feedChunks(payload: HandoffPayload, chunks: List<com.autocockpit.core.codec.Chunk>) =
        chunks.flatMap { server.onChunkRaw(it.encode()) }

    @Test
    fun happyPathCommitsThePayload() {
        newServer()
        val payload = TestPayloads.payload(12)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        assertTrue(begin(1L, payload, chunks.size).isEmpty())
        feedChunks(payload, chunks)
        val statuses = server.onControl(HandoffProtocol.encodeCommit(1L))
        val ok = statuses.filterIsInstance<StatusMessage.CommitOk>().single()
        assertTrue(!ok.alreadyCommitted)
        assertEquals(HandoffCodec.encode(payload).size, ok.bytes)
        assertEquals(1, ok.revision)
        assertEquals(payload, repository.current())
    }

    @Test
    fun schemaMismatchAbortsBeforeAcceptingChunks() {
        newServer()
        val payload = TestPayloads.payload(3)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        val statuses = begin(2L, payload.copy(schemaVersion = 2), chunks.size)
        val abort = statuses.filterIsInstance<StatusMessage.Abort>().single()
        assertEquals(AbortReason.SCHEMA_MISMATCH, abort.reason)
        assertEquals(1, server.schemaAborts)
        assertNull(repository.current())
        // A subsequent commit has no transfer to work on.
        val commit = server.onControl(HandoffProtocol.encodeCommit(2L))
        assertEquals(AbortReason.NO_TRANSFER, commit.filterIsInstance<StatusMessage.Abort>().single().reason)
    }

    @Test
    fun missingChunksProduceRetransmitList() {
        newServer()
        val payload = TestPayloads.payload(20)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(3L, payload, chunks.size)
        chunks.filterIndexed { index, _ -> index % 5 != 0 }.forEach { server.onChunkRaw(it.encode()) }
        val statuses = server.onControl(HandoffProtocol.encodeCommit(3L))
        val retransmit = statuses.filterIsInstance<StatusMessage.Retransmit>().single()
        assertEquals(chunks.filterIndexed { index, _ -> index % 5 == 0 }.map { it.index }, retransmit.missing)
        assertNull(repository.current())
    }

    @Test
    fun retransmittedChunksAllowCommit() {
        newServer()
        val payload = TestPayloads.payload(10)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(4L, payload, chunks.size)
        chunks.forEachIndexed { index, chunk -> if (index != 3) server.onChunkRaw(chunk.encode()) }
        val missing = server.onControl(HandoffProtocol.encodeCommit(4L))
            .filterIsInstance<StatusMessage.Retransmit>().single().missing
        assertEquals(listOf(3), missing)
        server.onChunkRaw(chunks[3].encode())
        val ok = server.onControl(HandoffProtocol.encodeCommit(4L))
            .filterIsInstance<StatusMessage.CommitOk>().single()
        assertTrue(!ok.alreadyCommitted)
        assertEquals(payload, repository.current())
    }

    @Test
    fun commitIsIdempotentPerTransferId() {
        newServer()
        val payload = TestPayloads.payload(5)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(5L, payload, chunks.size)
        chunks.forEach { server.onChunkRaw(it.encode()) }
        val first = server.onControl(HandoffProtocol.encodeCommit(5L))
            .filterIsInstance<StatusMessage.CommitOk>().single()
        val second = server.onControl(HandoffProtocol.encodeCommit(5L))
            .filterIsInstance<StatusMessage.CommitOk>().single()
        assertTrue(!first.alreadyCommitted)
        assertTrue(second.alreadyCommitted)
        assertEquals(first.revision, second.revision)
        assertEquals(1, repository.revision())
    }

    @Test
    fun repeatedBeginForCommittedTransferReturnsCommitOk() {
        newServer()
        val payload = TestPayloads.payload(4)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(6L, payload, chunks.size)
        chunks.forEach { server.onChunkRaw(it.encode()) }
        server.onControl(HandoffProtocol.encodeCommit(6L))
        val statuses = begin(6L, payload, chunks.size)
        assertTrue(statuses.filterIsInstance<StatusMessage.CommitOk>().single().alreadyCommitted)
    }

    @Test
    fun corruptFrameAbortsWithCrcMismatch() {
        newServer()
        val payload = TestPayloads.payload(8)
        val frame = HandoffCodec.encode(payload).copyOf()
        frame[frame.size - 3] = (frame[frame.size - 3].toInt() xor 0x33).toByte()
        val chunks = Chunker(20).split(frame)
        begin(7L, payload, chunks.size)
        chunks.forEach { server.onChunkRaw(it.encode()) }
        val abort = server.onControl(HandoffProtocol.encodeCommit(7L))
            .filterIsInstance<StatusMessage.Abort>().single()
        assertEquals(AbortReason.CRC_MISMATCH, abort.reason)
        assertEquals(1, server.crcAborts)
        assertNull(repository.current())
    }

    @Test
    fun oversizedFrameIsRejected() {
        newServer()
        val payload = TestPayloads.payload(2)
        val statuses = server.onControl(
            HandoffProtocol.encodeBegin(8L, payload.schemaVersion, 70_000, 4_000),
        )
        assertEquals(AbortReason.TOO_LARGE, statuses.filterIsInstance<StatusMessage.Abort>().single().reason)
    }

    @Test
    fun commitWithoutBeginIsRejected() {
        newServer()
        val statuses = server.onControl(HandoffProtocol.encodeCommit(99L))
        assertEquals(AbortReason.NO_TRANSFER, statuses.filterIsInstance<StatusMessage.Abort>().single().reason)
    }

    @Test
    fun resumeReturnsTheMissingChunkList() {
        newServer()
        val payload = TestPayloads.payload(30)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(9L, payload, chunks.size)
        chunks.take(5).forEach { server.onChunkRaw(it.encode()) }
        val info = server.onControl(HandoffProtocol.encodeResume(9L))
            .filterIsInstance<StatusMessage.ResumeInfo>().single()
        assertEquals(chunks.size, info.totalChunks)
        assertEquals(chunks.drop(5).map { it.index }, info.missing)
    }

    @Test
    fun resumeForUnknownTransferIsRejected() {
        newServer()
        val statuses = server.onControl(HandoffProtocol.encodeResume(123L))
        assertEquals(AbortReason.NO_TRANSFER, statuses.filterIsInstance<StatusMessage.Abort>().single().reason)
    }

    @Test
    fun duplicateChunksAreTolerated() {
        newServer()
        val payload = TestPayloads.payload(6)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(10L, payload, chunks.size)
        chunks.forEach { server.onChunkRaw(it.encode()) }
        chunks.forEach { server.onChunkRaw(it.encode()) }
        val ok = server.onControl(HandoffProtocol.encodeCommit(10L))
            .filterIsInstance<StatusMessage.CommitOk>().single()
        assertNotNull(repository.current())
        assertTrue(!ok.alreadyCommitted)
    }

    @Test
    fun progressNotificationsAreEmitted() {
        newServer()
        val payload = TestPayloads.payload(60)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(11L, payload, chunks.size)
        val statuses = chunks.flatMap { server.onChunkRaw(it.encode()) }
        assertTrue(statuses.filterIsInstance<StatusMessage.Progress>().isNotEmpty())
    }

    @Test
    fun clientAbortClearsTheTransfer() {
        newServer()
        val payload = TestPayloads.payload(3)
        val chunks = Chunker(20).split(HandoffCodec.encode(payload))
        begin(12L, payload, chunks.size)
        server.onControl(HandoffProtocol.encodeAbort(12L, AbortReason.TIMEOUT))
        val commit = server.onControl(HandoffProtocol.encodeCommit(12L))
        assertEquals(AbortReason.NO_TRANSFER, commit.filterIsInstance<StatusMessage.Abort>().single().reason)
    }

    @Test
    fun statusSnapshotAdvertisesSchemaAndMtu() {
        newServer()
        val ready = server.statusSnapshot(185)
        assertEquals(HandoffProtocol.SCHEMA_VERSION, ready.schemaVersion)
        assertEquals(185, ready.mtu)
        assertEquals(HandoffProtocol.MAX_FRAME_BYTES, ready.maxFrameBytes)
    }

    @Test
    fun garbageChunkBytesDoNotCrashTheServer() {
        newServer()
        val payload = TestPayloads.payload(2)
        begin(13L, payload, 4)
        val statuses = server.onChunkRaw(byteArrayOf(1, 2))
        assertEquals(AbortReason.PROTOCOL_ERROR, statuses.filterIsInstance<StatusMessage.Abort>().single().reason)
    }
}
