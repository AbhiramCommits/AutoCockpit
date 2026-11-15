package com.autocockpit.core.codec

import com.autocockpit.core.TestPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

    @Test
    fun chunkPayloadIsMtuMinusHeader() {
        assertEquals(16, Chunker(20).chunkPayloadBytes)
        assertEquals(19, Chunker(23).chunkPayloadBytes)
        assertEquals(181, Chunker(185).chunkPayloadBytes)
    }

    @Test
    fun singleChunkWhenFrameFits() {
        val chunks = Chunker(20).split(ByteArray(16) { it.toByte() })
        assertEquals(1, chunks.size)
        assertEquals(1, chunks[0].total)
        assertEquals(0, chunks[0].index)
        assertEquals(20, chunks[0].encode().size)
    }

    @Test
    fun exactMtuBoundaryProducesTwoFullChunks() {
        val chunks = Chunker(20).split(ByteArray(32) { it.toByte() })
        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].encode().size)
        assertEquals(20, chunks[1].encode().size)
    }

    @Test
    fun oneByteOverBoundaryAddsAThirdChunk() {
        val chunks = Chunker(20).split(ByteArray(33) { it.toByte() })
        assertEquals(3, chunks.size)
        // 16 + 16 + 1 payload bytes, each chunk carries the 4-byte header.
        assertEquals(5, chunks[2].encode().size)
        assertEquals(1, chunks[2].data.size)
    }

    @Test
    fun chunkCountMatchesSplitSize() {
        val chunker = Chunker(64)
        for (size in intArrayOf(1, 59, 60, 61, 120, 121, 500)) {
            assertEquals(size, chunker.split(ByteArray(size)).sumOf { it.data.size })
            assertEquals(chunker.chunkCount(size), chunker.split(ByteArray(size)).size)
        }
    }

    @Test
    fun chunkHeaderEncodesIndexAndTotal() {
        val chunk = Chunker(20).split(ByteArray(40)).last()
        val encoded = chunk.encode()
        val decoded = Chunk.decode(encoded)
        assertEquals(chunk.index, decoded.index)
        assertEquals(chunk.total, decoded.total)
        assertEquals(3, decoded.total)
        assertEquals(2, decoded.index)
        assertTrue(chunk.data.contentEquals(decoded.data))
    }

    @Test
    fun mtuBelowMinimumIsRejected() {
        val error = runCatching { Chunker(7) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun mtuAboveMaximumIsRejected() {
        val error = runCatching { Chunker(513) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun emptyFrameCannotBeSplit() {
        val error = runCatching { Chunker(20).split(ByteArray(0)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun reassemblyInOrderSucceeds() {
        val frame = HandoffCodec.encode(TestPayloads.payload(20))
        val chunks = Chunker(20).split(frame)
        val reassembler = Reassembler()
        chunks.forEach { reassembler.offer(it) }
        assertTrue(reassembler.isComplete())
        val decoded = reassembler.reassemble()
        assertTrue(decoded is DecodeResult.Success)
    }

    @Test
    fun reassemblyOutOfOrderSucceedsAndCountsAnomalies() {
        val frame = HandoffCodec.encode(TestPayloads.payload(8))
        val chunks = Chunker(20).split(frame)
        val reassembler = Reassembler()
        val reordered = chunks.reversed()
        reordered.forEach { reassembler.offer(it) }
        assertTrue(reassembler.isComplete())
        assertTrue(reassembler.outOfOrderChunks > 0)
        assertTrue(reassembler.reassemble() is DecodeResult.Success)
    }

    @Test
    fun duplicateChunkIsReportedAndIgnored() {
        val chunks = Chunker(20).split(HandoffCodec.encode(TestPayloads.payload(3)))
        val reassembler = Reassembler()
        reassembler.offer(chunks[0])
        val ack = reassembler.offer(chunks[0])
        assertTrue(ack is ChunkAck.Duplicate)
        assertEquals(1, reassembler.receivedChunks)
        assertEquals(1, reassembler.duplicateChunks)
    }

    @Test
    fun missingChunkYieldsIncompleteFailure() {
        val chunks = Chunker(20).split(HandoffCodec.encode(TestPayloads.payload(10)))
        val reassembler = Reassembler()
        chunks.drop(2).forEach { reassembler.offer(it) }
        assertFalse(reassembler.isComplete())
        assertEquals(listOf(0, 1), reassembler.missing())
        assertEquals(DecodeFailure.INCOMPLETE, (reassembler.reassemble() as DecodeResult.Failure).reason)
    }

    @Test
    fun totalMismatchIsRejected() {
        val reassembler = Reassembler()
        reassembler.offer(0, 3, byteArrayOf(1))
        val ack = reassembler.offer(1, 4, byteArrayOf(2))
        assertTrue(ack is ChunkAck.TotalMismatch)
        assertEquals(3, (ack as ChunkAck.TotalMismatch).expectedTotal)
    }

    @Test
    fun indexOutOfRangeIsRejected() {
        val reassembler = Reassembler()
        val ack = reassembler.offer(5, 3, byteArrayOf(1))
        assertTrue(ack is ChunkAck.IndexOutOfRange)
    }

    @Test
    fun emptyChunkDataIsRejected() {
        val reassembler = Reassembler()
        val ack = reassembler.offer(0, 2, ByteArray(0))
        assertTrue(ack is ChunkAck.IndexOutOfRange)
    }

    @Test
    fun completeAckIsReturnedOnLastChunk() {
        val chunks = Chunker(20).split(HandoffCodec.encode(TestPayloads.payload(2)))
        val reassembler = Reassembler()
        var last: ChunkAck? = null
        chunks.forEach { last = reassembler.offer(it) }
        assertTrue("ack was $last", last is ChunkAck.Complete)
    }

    @Test
    fun tamperedChunkFailsCrc() {
        val frame = HandoffCodec.encode(TestPayloads.payload(10))
        val chunks = Chunker(20).split(frame).toMutableList()
        val target = chunks[1]
        val data = target.data.copyOf()
        data[0] = (data[0].toInt() xor 0x5A).toByte()
        chunks[1] = Chunk(target.index, target.total, data)
        val reassembler = Reassembler()
        chunks.forEach { reassembler.offer(it) }
        val decoded = reassembler.reassemble()
        assertEquals(DecodeFailure.CRC_MISMATCH, (decoded as DecodeResult.Failure).reason)
    }

    @Test
    fun largeMtuRoundTripsLargePayload() {
        val payload = TestPayloads.payload(500)
        val chunks = Chunker(185).split(HandoffCodec.encode(payload))
        val reassembler = Reassembler()
        chunks.forEach { reassembler.offer(it) }
        val decoded = reassembler.reassemble()
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(payload, (decoded as DecodeResult.Success).payload)
    }

    @Test
    fun smallMtuRoundTripsPayload() {
        val payload = TestPayloads.payload(5)
        val chunks = Chunker(8).split(HandoffCodec.encode(payload))
        val reassembler = Reassembler()
        chunks.reversed().forEach { reassembler.offer(it) }
        assertEquals(payload, (reassembler.reassemble() as DecodeResult.Success).payload)
    }

    @Test
    fun resetClearsPartialState() {
        val chunks = Chunker(20).split(HandoffCodec.encode(TestPayloads.payload(4)))
        val reassembler = Reassembler()
        chunks.take(2).forEach { reassembler.offer(it) }
        reassembler.reset()
        assertEquals(0, reassembler.receivedChunks)
        assertEquals(0, reassembler.totalChunks)
        assertTrue(reassembler.reassemble() is DecodeResult.Failure)
    }

    @Test
    fun chunkEqualityIsStructural() {
        val a = Chunk(1, 4, byteArrayOf(9, 8, 7))
        val b = Chunk(1, 4, byteArrayOf(9, 8, 7))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, Chunk(1, 4, byteArrayOf(9, 8, 6)))
    }

    @Test
    fun chunkAccessorBoundsAreEnforced() {
        val error = runCatching { Chunk(index = 4, total = 4, data = byteArrayOf(1)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
