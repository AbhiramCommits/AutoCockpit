package com.autocockpit.core.codec

import com.autocockpit.core.BuildInfo

/**
 * One BLE transfer unit. The 4-byte header is `index u16` + `total u16`, both big endian,
 * followed by up to `mtu - 4` bytes of frame payload.
 */
data class Chunk(
    val index: Int,
    val total: Int,
    val data: ByteArray,
) {
    init {
        require(index in 0 until total) { "index $index outside 0..<$total" }
        require(data.isNotEmpty()) { "chunk must carry data" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(Chunker.CHUNK_HEADER_BYTES + data.size)
        out[0] = ((index ushr 8) and 0xFF).toByte()
        out[1] = (index and 0xFF).toByte()
        out[2] = ((total ushr 8) and 0xFF).toByte()
        out[3] = (total and 0xFF).toByte()
        data.copyInto(out, Chunker.CHUNK_HEADER_BYTES)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Chunk) return false
        return index == other.index && total == other.total && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * (31 * index + total) + data.contentHashCode()

    companion object {
        fun decode(raw: ByteArray): Chunk {
            require(raw.size >= Chunker.CHUNK_HEADER_BYTES) { "chunk shorter than header: ${raw.size}" }
            val index = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
            val total = ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
            val data = raw.copyOfRange(Chunker.CHUNK_HEADER_BYTES, raw.size)
            return Chunk(index, total, data)
        }
    }
}

/** Splits a frame into MTU-sized chunks. */
class Chunker(val mtu: Int = BuildInfo.DEFAULT_ATT_PAYLOAD_BYTES) {
    val chunkPayloadBytes: Int = mtu - CHUNK_HEADER_BYTES

    init {
        require(mtu in MIN_MTU..MAX_MTU) { "mtu $mtu outside $MIN_MTU..$MAX_MTU" }
        require(chunkPayloadBytes > 0) { "mtu $mtu leaves no room for payload" }
    }

    fun chunkCount(frameBytes: Int): Int {
        if (frameBytes <= 0) return 0
        return (frameBytes + chunkPayloadBytes - 1) / chunkPayloadBytes
    }

    fun split(frame: ByteArray): List<Chunk> {
        require(frame.isNotEmpty()) { "cannot chunk an empty frame" }
        val total = chunkCount(frame.size)
        require(total <= 0xFFFF) { "frame requires $total chunks, more than a u16 can index" }
        val chunks = ArrayList<Chunk>(total)
        var offset = 0
        for (index in 0 until total) {
            val end = minOf(offset + chunkPayloadBytes, frame.size)
            chunks += Chunk(index, total, frame.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    companion object {
        const val CHUNK_HEADER_BYTES: Int = 4
        const val MIN_MTU: Int = 8
        const val MAX_MTU: Int = 512
    }
}

sealed interface ChunkAck {
    data class Accepted(val received: Int, val total: Int) : ChunkAck
    data class Duplicate(val index: Int, val received: Int, val total: Int) : ChunkAck
    data class Complete(val total: Int, val bytes: Int) : ChunkAck
    data class TotalMismatch(val expectedTotal: Int, val offeredTotal: Int) : ChunkAck
    data class IndexOutOfRange(val index: Int, val total: Int) : ChunkAck
}

data class ReassemblySnapshot(
    val totalChunks: Int,
    val receivedChunks: Int,
    val duplicateChunks: Int,
    val outOfOrderChunks: Int,
    val rejectedChunks: Int,
    val bytes: Int,
) {
    fun missingCount(): Int = (totalChunks - receivedChunks).coerceAtLeast(0)
}

/**
 * Rebuilds a frame from chunks arriving in any order, tolerating duplicates. It never throws on
 * hostile input: every anomaly is reported through [ChunkAck] or a [DecodeResult.Failure].
 */
class Reassembler {
    private val parts = HashMap<Int, ByteArray>()
    private var declaredTotal = -1
    private var highestIndexSeen = -1

    var duplicateChunks: Int = 0
        private set
    var outOfOrderChunks: Int = 0
        private set
    var rejectedChunks: Int = 0
        private set

    val totalChunks: Int get() = declaredTotal.coerceAtLeast(0)
    val receivedChunks: Int get() = parts.size
    val receivedBytes: Int get() = parts.values.sumOf { it.size }

    fun offer(index: Int, total: Int, data: ByteArray): ChunkAck {
        if (total <= 0 || index < 0 || index >= total) {
            rejectedChunks++
            return ChunkAck.IndexOutOfRange(index, total)
        }
        if (declaredTotal >= 0 && declaredTotal != total) {
            rejectedChunks++
            return ChunkAck.TotalMismatch(declaredTotal, total)
        }
        if (declaredTotal < 0) declaredTotal = total
        if (parts.containsKey(index)) {
            duplicateChunks++
            return ChunkAck.Duplicate(index, parts.size, declaredTotal)
        }
        if (data.isEmpty()) {
            rejectedChunks++
            return ChunkAck.IndexOutOfRange(index, total)
        }
        // Out of order means "arrived after a chunk with a higher index"; an in-order transfer
        // with gaps is not out of order, it is lossy.
        if (index < highestIndexSeen) outOfOrderChunks++
        if (index > highestIndexSeen) highestIndexSeen = index
        parts[index] = data
        return if (parts.size == declaredTotal) {
            ChunkAck.Complete(declaredTotal, receivedBytes)
        } else {
            ChunkAck.Accepted(parts.size, declaredTotal)
        }
    }

    fun offer(chunk: Chunk): ChunkAck = offer(chunk.index, chunk.total, chunk.data)

    fun isComplete(): Boolean = declaredTotal > 0 && parts.size == declaredTotal

    fun missing(): List<Int> {
        if (declaredTotal <= 0) return emptyList()
        return (0 until declaredTotal).filter { !parts.containsKey(it) }
    }

    fun snapshot(): ReassemblySnapshot = ReassemblySnapshot(
        totalChunks = declaredTotal.coerceAtLeast(0),
        receivedChunks = parts.size,
        duplicateChunks = duplicateChunks,
        outOfOrderChunks = outOfOrderChunks,
        rejectedChunks = rejectedChunks,
        bytes = receivedBytes,
    )

    /** Concatenates and decodes. Returns INCOMPLETE while chunks are missing. */
    fun reassemble(): DecodeResult {
        val missing = missing()
        if (missing.isNotEmpty()) {
            return DecodeResult.Failure(
                DecodeFailure.INCOMPLETE,
                "missing ${missing.size}/$declaredTotal chunks: ${missing.take(8)}",
            )
        }
        if (declaredTotal <= 0) {
            return DecodeResult.Failure(DecodeFailure.INCOMPLETE, "no chunks received")
        }
        val frame = ByteArray(receivedBytes)
        var offset = 0
        for (index in 0 until declaredTotal) {
            val part = parts[index] ?: return DecodeResult.Failure(
                DecodeFailure.INCOMPLETE,
                "chunk $index vanished during reassembly",
            )
            part.copyInto(frame, offset)
            offset += part.size
        }
        return HandoffCodec.decode(frame)
    }

    fun reset() {
        parts.clear()
        declaredTotal = -1
        highestIndexSeen = -1
        duplicateChunks = 0
        outOfOrderChunks = 0
        rejectedChunks = 0
    }
}
