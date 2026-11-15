package com.autocockpit.core.handoff

import com.autocockpit.core.codec.Chunk
import com.autocockpit.core.codec.DecodeFailure
import com.autocockpit.core.codec.DecodeResult
import com.autocockpit.core.codec.Reassembler

/**
 * Protocol engine for the head-unit side of the handoff. Pure JVM: the Android
 * `HandoffGattServer` only moves bytes between BluetoothGattServer and this class, so the exact
 * same reassembly, retransmit and commit logic runs headless in tests and the simulation.
 *
 * Guarantees:
 *  - a schema mismatch aborts before any chunk is accepted,
 *  - missing chunks produce a retransmit bitmap instead of a commit,
 *  - COMMIT is idempotent per transfer id (a repeated commit returns the first result),
 *  - a CRC failure aborts the transfer (the phone restarts with a new transfer id).
 */
class HandoffServerCore(
    private val repository: HandoffRepository,
    private val maxFrameBytes: Int = HandoffProtocol.MAX_FRAME_BYTES,
    private val progressInterval: Int = 32,
) {
    private class Transfer(
        val id: Long,
        val schemaVersion: Int,
        val frameBytes: Int,
        val totalChunks: Int,
        val reassembler: Reassembler = Reassembler(),
        var lastDuplicates: Int = 0,
        var lastOutOfOrder: Int = 0,
    )

    data class CommitRecord(val revision: Int, val bytes: Int)

    private var transfer: Transfer? = null
    private val commits = LinkedHashMap<Long, CommitRecord>()
    private var chunksSinceProgress = 0

    var committedPayloads: Int = 0
        private set
    var schemaAborts: Int = 0
        private set
    var crcAborts: Int = 0
        private set
    var retransmitRequests: Int = 0
        private set
    var duplicateChunks: Int = 0
        private set
    var outOfOrderChunks: Int = 0
        private set

    fun statusSnapshot(mtu: Int): StatusMessage.Ready = StatusMessage.Ready(
        schemaVersion = HandoffProtocol.SCHEMA_VERSION,
        mtu = mtu,
        maxFrameBytes = maxFrameBytes,
    )

    fun onControl(bytes: ByteArray): List<StatusMessage> = when (val message = HandoffProtocol.decodeControl(bytes)) {
        is ControlMessage.Begin -> onBegin(message)
        is ControlMessage.Commit -> onCommit(message.transferId)
        is ControlMessage.Abort -> {
            transfer = null
            emptyList()
        }
        is ControlMessage.Resume -> onResume(message.transferId)
        is ControlMessage.Unknown -> listOf(StatusMessage.Abort(0, AbortReason.PROTOCOL_ERROR))
    }

    /**
     * Accepts a chunk. [mtu] is only used for the progress/ready payload and is optional here.
     */
    fun onChunk(index: Int, total: Int, data: ByteArray): List<StatusMessage> {
        val current = transfer ?: return listOf(StatusMessage.Abort(0, AbortReason.NO_TRANSFER))
        if (total != current.totalChunks) {
            return listOf(StatusMessage.Abort(current.id, AbortReason.PROTOCOL_ERROR))
        }
        val ack = current.reassembler.offer(index, total, data)
        duplicateChunks += current.reassembler.duplicateChunks - current.lastDuplicates
        outOfOrderChunks += current.reassembler.outOfOrderChunks - current.lastOutOfOrder
        current.lastDuplicates = current.reassembler.duplicateChunks
        current.lastOutOfOrder = current.reassembler.outOfOrderChunks
        chunksSinceProgress++
        val sendProgress = chunksSinceProgress >= progressInterval ||
            ack is com.autocockpit.core.codec.ChunkAck.Complete
        if (sendProgress) chunksSinceProgress = 0
        return when (ack) {
            is com.autocockpit.core.codec.ChunkAck.IndexOutOfRange,
            is com.autocockpit.core.codec.ChunkAck.TotalMismatch,
            -> listOf(StatusMessage.Abort(current.id, AbortReason.PROTOCOL_ERROR))
            else -> if (sendProgress) {
                listOf(StatusMessage.Progress(current.id, current.reassembler.receivedChunks, current.totalChunks))
            } else {
                emptyList()
            }
        }
    }

    fun onChunkRaw(raw: ByteArray): List<StatusMessage> {
        val chunk = runCatching { Chunk.decode(raw) }.getOrElse {
            return listOf(StatusMessage.Abort(transfer?.id ?: 0, AbortReason.PROTOCOL_ERROR))
        }
        return onChunk(chunk.index, chunk.total, chunk.data)
    }

    private fun onBegin(message: ControlMessage.Begin): List<StatusMessage> {
        if (message.schemaVersion != HandoffProtocol.SCHEMA_VERSION) {
            schemaAborts++
            return listOf(StatusMessage.Abort(message.transferId, AbortReason.SCHEMA_MISMATCH))
        }
        if (message.frameBytes <= 0 || message.frameBytes > maxFrameBytes) {
            return listOf(StatusMessage.Abort(message.transferId, AbortReason.TOO_LARGE))
        }
        if (message.totalChunks <= 0 || message.totalChunks > 0xFFFF) {
            return listOf(StatusMessage.Abort(message.transferId, AbortReason.PROTOCOL_ERROR))
        }
        commits[message.transferId]?.let { record ->
            return listOf(StatusMessage.CommitOk(message.transferId, record.bytes, record.revision, alreadyCommitted = true))
        }
        val existing = transfer
        if (existing != null && existing.id == message.transferId && existing.frameBytes == message.frameBytes) {
            return listOf(StatusMessage.ResumeInfo(existing.id, existing.totalChunks, existing.reassembler.missing()))
        }
        val created = Transfer(message.transferId, message.schemaVersion, message.frameBytes, message.totalChunks)
        created.reassembler.expectTotal(message.totalChunks)
        transfer = created
        chunksSinceProgress = 0
        return emptyList()
    }

    private fun onCommit(transferId: Long): List<StatusMessage> {
        commits[transferId]?.let { record ->
            return listOf(StatusMessage.CommitOk(transferId, record.bytes, record.revision, alreadyCommitted = true))
        }
        val current = transfer
        if (current == null || current.id != transferId) {
            return listOf(StatusMessage.Abort(transferId, AbortReason.NO_TRANSFER))
        }
        if (!current.reassembler.isComplete()) {
            retransmitRequests++
            return listOf(StatusMessage.Retransmit(transferId, current.reassembler.missing()))
        }
        return when (val decoded = current.reassembler.reassemble()) {
            is DecodeResult.Success -> {
                val commit = repository.commit(decoded.payload)
                commits[transferId] = CommitRecord(commit.revision, decoded.frameBytes)
                committedPayloads++
                transfer = null
                listOf(
                    StatusMessage.CommitOk(
                        transferId = transferId,
                        bytes = decoded.frameBytes,
                        revision = commit.revision,
                        alreadyCommitted = false,
                    ),
                )
            }
            is DecodeResult.Failure -> when (decoded.reason) {
                DecodeFailure.CRC_MISMATCH -> {
                    crcAborts++
                    transfer = null
                    listOf(StatusMessage.Abort(transferId, AbortReason.CRC_MISMATCH))
                }
                DecodeFailure.UNSUPPORTED_SCHEMA -> {
                    schemaAborts++
                    transfer = null
                    listOf(StatusMessage.Abort(transferId, AbortReason.SCHEMA_MISMATCH))
                }
                DecodeFailure.INCOMPLETE -> {
                    retransmitRequests++
                    listOf(StatusMessage.Retransmit(transferId, current.reassembler.missing()))
                }
                else -> {
                    transfer = null
                    listOf(StatusMessage.Abort(transferId, AbortReason.PROTOCOL_ERROR))
                }
            }
        }
    }

    private fun onResume(transferId: Long): List<StatusMessage> {
        commits[transferId]?.let { record ->
            return listOf(StatusMessage.CommitOk(transferId, record.bytes, record.revision, alreadyCommitted = true))
        }
        val current = transfer
        if (current == null || current.id != transferId) {
            return listOf(StatusMessage.Abort(transferId, AbortReason.NO_TRANSFER))
        }
        return listOf(StatusMessage.ResumeInfo(transferId, current.totalChunks, current.reassembler.missing()))
    }

    fun reset() {
        transfer = null
    }
}
