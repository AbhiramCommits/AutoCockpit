package com.autocockpit.core.handoff

import com.autocockpit.core.BuildInfo
import com.autocockpit.core.codec.ByteReader
import com.autocockpit.core.codec.ByteWriter
import java.util.UUID

/** Fixed BLE identities for the companion handoff service. */
object HandoffProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("a11c0c70-7e11-4a01-9d00-000000000001")
    val WRITE_UUID: UUID = UUID.fromString("a11c0c70-7e11-4a01-9d00-000000000002")
    val CONTROL_UUID: UUID = UUID.fromString("a11c0c70-7e11-4a01-9d00-000000000003")
    val STATUS_UUID: UUID = UUID.fromString("a11c0c70-7e11-4a01-9d00-000000000004")

    const val SCHEMA_VERSION: Int = BuildInfo.SCHEMA_VERSION
    const val DEFAULT_MTU: Int = 23
    const val REQUESTED_MTU: Int = 185
    const val MAX_MTU: Int = 247
    const val ATT_HEADER_BYTES: Int = 3
    const val MAX_FRAME_BYTES: Int = 0xFFFF

    // Control opcodes (phone -> head unit).
    const val CONTROL_BEGIN: Int = 0x01
    const val CONTROL_COMMIT: Int = 0x02
    const val CONTROL_ABORT: Int = 0x03
    const val CONTROL_RESUME: Int = 0x04

    // Status opcodes (head unit -> phone notifications / control read).
    const val STATUS_READY: Int = 0x10
    const val STATUS_PROGRESS: Int = 0x11
    const val STATUS_RETRANSMIT: Int = 0x12
    const val STATUS_COMMIT_OK: Int = 0x13
    const val STATUS_ABORT: Int = 0x14
    const val STATUS_RESUME_INFO: Int = 0x15

    fun encodeBegin(transferId: Long, schemaVersion: Int, frameBytes: Int, totalChunks: Int): ByteArray =
        ByteWriter(16).apply {
            u8(CONTROL_BEGIN)
            u32(transferId)
            u16(schemaVersion)
            u32(frameBytes.toLong())
            u16(totalChunks)
        }.toByteArray()

    fun encodeCommit(transferId: Long): ByteArray = ByteWriter(8).apply {
        u8(CONTROL_COMMIT)
        u32(transferId)
    }.toByteArray()

    fun encodeAbort(transferId: Long, reason: AbortReason): ByteArray = ByteWriter(8).apply {
        u8(CONTROL_ABORT)
        u32(transferId)
        u8(reason.code)
    }.toByteArray()

    fun encodeResume(transferId: Long): ByteArray = ByteWriter(8).apply {
        u8(CONTROL_RESUME)
        u32(transferId)
    }.toByteArray()

    fun decodeControl(bytes: ByteArray): ControlMessage = try {
        val reader = ByteReader(bytes)
        when (val opcode = reader.u8()) {
            CONTROL_BEGIN -> ControlMessage.Begin(
                transferId = reader.u32(),
                schemaVersion = reader.u16(),
                frameBytes = reader.u32().toInt(),
                totalChunks = reader.u16(),
            )
            CONTROL_COMMIT -> ControlMessage.Commit(reader.u32())
            CONTROL_ABORT -> ControlMessage.Abort(reader.u32(), AbortReason.fromCode(reader.u8()))
            CONTROL_RESUME -> ControlMessage.Resume(reader.u32())
            else -> ControlMessage.Unknown(opcode)
        }
    } catch (e: Exception) {
        ControlMessage.Unknown(-1)
    }

    fun encodeStatus(status: StatusMessage): ByteArray {
        val writer = ByteWriter(32)
        when (status) {
            is StatusMessage.Ready -> {
                writer.u8(STATUS_READY)
                writer.u16(status.schemaVersion)
                writer.u16(status.mtu)
                writer.u32(status.maxFrameBytes.toLong())
            }
            is StatusMessage.Progress -> {
                writer.u8(STATUS_PROGRESS)
                writer.u32(status.transferId)
                writer.u16(status.receivedChunks)
                writer.u16(status.totalChunks)
            }
            is StatusMessage.Retransmit -> {
                writer.u8(STATUS_RETRANSMIT)
                writer.u32(status.transferId)
                writer.u16(status.missing.size)
                status.missing.forEach { writer.u16(it) }
            }
            is StatusMessage.CommitOk -> {
                writer.u8(STATUS_COMMIT_OK)
                writer.u32(status.transferId)
                writer.u32(status.bytes.toLong())
                writer.u32(status.revision.toLong())
                writer.u8(if (status.alreadyCommitted) 1 else 0)
            }
            is StatusMessage.Abort -> {
                writer.u8(STATUS_ABORT)
                writer.u32(status.transferId)
                writer.u8(status.reason.code)
            }
            is StatusMessage.ResumeInfo -> {
                writer.u8(STATUS_RESUME_INFO)
                writer.u32(status.transferId)
                writer.u16(status.totalChunks)
                writer.u16(status.missing.size)
                status.missing.forEach { writer.u16(it) }
            }
        }
        return writer.toByteArray()
    }

    fun decodeStatus(bytes: ByteArray): StatusMessage? = try {
        val reader = ByteReader(bytes)
        when (val opcode = reader.u8()) {
            STATUS_READY -> StatusMessage.Ready(reader.u16(), reader.u16(), reader.u32().toInt())
            STATUS_PROGRESS -> StatusMessage.Progress(reader.u32(), reader.u16(), reader.u16())
            STATUS_RETRANSMIT -> {
                val id = reader.u32()
                val count = reader.u16()
                StatusMessage.Retransmit(id, (0 until count).map { reader.u16() })
            }
            STATUS_COMMIT_OK -> StatusMessage.CommitOk(
                transferId = reader.u32(),
                bytes = reader.u32().toInt(),
                revision = reader.u32().toInt(),
                alreadyCommitted = reader.u8() == 1,
            )
            STATUS_ABORT -> StatusMessage.Abort(reader.u32(), AbortReason.fromCode(reader.u8()))
            STATUS_RESUME_INFO -> {
                val id = reader.u32()
                val total = reader.u16()
                val count = reader.u16()
                StatusMessage.ResumeInfo(id, total, (0 until count).map { reader.u16() })
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

sealed interface ControlMessage {
    data class Begin(val transferId: Long, val schemaVersion: Int, val frameBytes: Int, val totalChunks: Int) : ControlMessage
    data class Commit(val transferId: Long) : ControlMessage
    data class Abort(val transferId: Long, val reason: AbortReason) : ControlMessage
    data class Resume(val transferId: Long) : ControlMessage
    data class Unknown(val opcode: Int) : ControlMessage
}

sealed interface StatusMessage {
    data class Ready(val schemaVersion: Int, val mtu: Int, val maxFrameBytes: Int) : StatusMessage
    data class Progress(val transferId: Long, val receivedChunks: Int, val totalChunks: Int) : StatusMessage
    data class Retransmit(val transferId: Long, val missing: List<Int>) : StatusMessage
    data class CommitOk(val transferId: Long, val bytes: Int, val revision: Int, val alreadyCommitted: Boolean) : StatusMessage
    data class Abort(val transferId: Long, val reason: AbortReason) : StatusMessage
    data class ResumeInfo(val transferId: Long, val totalChunks: Int, val missing: List<Int>) : StatusMessage
}

enum class AbortReason(val code: Int) {
    SCHEMA_MISMATCH(1),
    TOO_LARGE(2),
    CRC_MISMATCH(3),
    PROTOCOL_ERROR(4),
    NO_TRANSFER(5),
    INCOMPLETE(6),
    TIMEOUT(7);

    companion object {
        fun fromCode(code: Int): AbortReason = entries.firstOrNull { it.code == code } ?: PROTOCOL_ERROR
    }
}

/** BLE characteristics used by the handoff channel. */
enum class HandoffCharacteristic {
    /** write-without-response: chunked frame payload in */
    WRITE,

    /** write: BEGIN/COMMIT/ABORT/RESUME; read: schema version + MTU + max frame */
    CONTROL,

    /** notify: progress, retransmit requests, commit result, abort */
    STATUS,
}
