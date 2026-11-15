package com.autocockpit.core.handoff

import com.autocockpit.core.codec.Chunker
import com.autocockpit.core.codec.HandoffCodec
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.link.BackoffPolicy
import com.autocockpit.core.link.LinkEffect
import com.autocockpit.core.link.LinkEvent
import com.autocockpit.core.link.LinkStateMachine
import kotlin.random.Random

/** Outcome of one phone-to-head-unit handoff attempt. */
data class HandoffResult(
    val success: Boolean,
    val transferId: Long,
    val frameBytes: Int,
    val bytesTransferred: Int,
    val chunksSent: Int,
    val retransmits: Int,
    val reconnectAttempts: Int,
    val recoveryLatencyMs: Long,
    val durationMs: Long,
    val restarts: Int,
    val crcAborts: Int,
    val negotiatedMtu: Int,
    val abortReason: AbortReason? = null,
    val failureReason: String? = null,
)

/**
 * Phone-side handoff driver: negotiates the MTU, chunks the encoded payload, streams chunks over
 * the WRITE characteristic, and resolves the head unit's status notifications.
 *
 * Recovery behaviour:
 *  - a failed write means the link dropped: the [LinkStateMachine] produces the backoff delay,
 *    the client reconnects, negotiates again and resumes from the server's missing-chunk list;
 *  - missing chunks reported at COMMIT are retransmitted and COMMIT is retried;
 *  - a CRC abort restarts the frame under a fresh transfer id (bounded by `maxRestarts`);
 *  - a schema mismatch aborts immediately, it is never retried.
 */
class CompanionClient(
    private val transport: HandoffTransport,
    private val link: LinkStateMachine = LinkStateMachine(BackoffPolicy(), Random(11)),
    private val requestedMtu: Int = HandoffProtocol.REQUESTED_MTU,
    private val maxCommitRounds: Int = 16,
    private val maxRestarts: Int = 3,
) {

    fun send(payload: HandoffPayload, initialTransferId: Long = 1L): HandoffResult {
        val startMillis = transport.nowMillis()
        var bytes = 0
        var chunksSent = 0
        var retransmits = 0
        var attempts = 0
        var recoveryMs = 0L
        var restarts = 0
        var crcAborts = 0
        var transferId = initialTransferId

        fun write(characteristic: HandoffCharacteristic, value: ByteArray): Boolean {
            val ok = transport.writeCharacteristic(characteristic, value)
            if (ok) bytes += value.size
            return ok
        }

        fun drain(): List<StatusMessage> = transport.notifications().mapNotNull(HandoffProtocol::decodeStatus)

        fun failure(reason: String, abort: AbortReason? = null, mtu: Int = 0): HandoffResult = HandoffResult(
            success = false,
            transferId = transferId,
            frameBytes = 0,
            bytesTransferred = bytes,
            chunksSent = chunksSent,
            retransmits = retransmits,
            reconnectAttempts = attempts,
            recoveryLatencyMs = recoveryMs,
            durationMs = transport.nowMillis() - startMillis,
            restarts = restarts,
            crcAborts = crcAborts,
            negotiatedMtu = mtu,
            abortReason = abort,
            failureReason = reason,
        )

        link.on(LinkEvent.StartRequested)
        link.on(LinkEvent.PeerDiscovered)
        if (!transport.connect()) {
            link.on(LinkEvent.ConnectFailed)
            return failure("initial connect failed")
        }
        link.on(LinkEvent.Connected)
        val negotiatedMtu = transport.negotiateMtu(requestedMtu)
        if (negotiatedMtu < HandoffProtocol.DEFAULT_MTU) {
            link.on(LinkEvent.MtuNegotiationFailed)
            return failure("mtu negotiation failed", mtu = negotiatedMtu)
        }
        link.on(LinkEvent.MtuNegotiated(negotiatedMtu))

        val frame = HandoffCodec.encode(payload)
        val chunker = Chunker(negotiatedMtu - HandoffProtocol.ATT_HEADER_BYTES)
        val chunks = chunker.split(frame)

        var missing = chunks.indices.toList()
        var begun = false
        var rounds = 0

        /** Reconnects and returns the missing chunk list, or null when the retry budget is spent. */
        fun recover(): List<Int>? {
            while (attempts < link.maxAttempts) {
                val lost = link.on(LinkEvent.LinkLost)
                if (lost.snapshot.exhausted) return null
                val delay = lost.effects.filterIsInstance<LinkEffect.ScheduleReconnect>()
                    .firstOrNull()?.delayMillis ?: 500L
                attempts++
                recoveryMs += delay
                transport.disconnect()
                link.on(LinkEvent.ReconnectTick)
                link.on(LinkEvent.PeerDiscovered)
                if (!transport.connect()) {
                    link.on(LinkEvent.ConnectFailed)
                    continue
                }
                link.on(LinkEvent.Connected)
                val mtu = transport.negotiateMtu(requestedMtu)
                if (mtu < HandoffProtocol.DEFAULT_MTU) {
                    link.on(LinkEvent.MtuNegotiationFailed)
                    continue
                }
                link.on(LinkEvent.MtuNegotiated(mtu))
                write(HandoffCharacteristic.CONTROL, HandoffProtocol.encodeResume(transferId))
                val statuses = drain()
                val abort = statuses.filterIsInstance<StatusMessage.Abort>().firstOrNull()
                if (abort != null) {
                    // The server forgot the transfer (e.g. it restarted): begin again.
                    begun = false
                    return chunks.indices.toList()
                }
                val resume = statuses.filterIsInstance<StatusMessage.ResumeInfo>().firstOrNull()
                return resume?.missing ?: chunks.indices.toList()
            }
            return null
        }

        while (rounds <= maxCommitRounds) {
            if (!begun) {
                write(
                    HandoffCharacteristic.CONTROL,
                    HandoffProtocol.encodeBegin(transferId, payload.schemaVersion, frame.size, chunks.size),
                )
                val beginStatus = drain()
                val abort = beginStatus.filterIsInstance<StatusMessage.Abort>().firstOrNull()
                if (abort != null) {
                    link.on(LinkEvent.SchemaMismatch(payload.schemaVersion, HandoffProtocol.SCHEMA_VERSION))
                    return failure("begin aborted: ${abort.reason}", abort.reason, negotiatedMtu)
                }
                // A repeated BEGIN for an already committed transfer is idempotent.
                if (beginStatus.any { it is StatusMessage.CommitOk }) {
                    link.on(LinkEvent.SyncCompleted)
                    return HandoffResult(
                        success = true,
                        transferId = transferId,
                        frameBytes = frame.size,
                        bytesTransferred = bytes,
                        chunksSent = chunksSent,
                        retransmits = retransmits,
                        reconnectAttempts = attempts,
                        recoveryLatencyMs = recoveryMs,
                        durationMs = transport.nowMillis() - startMillis,
                        restarts = restarts,
                        crcAborts = crcAborts,
                        negotiatedMtu = negotiatedMtu,
                    )
                }
                begun = true
            }

            var index = 0
            var needRecovery = false
            while (index < missing.size) {
                val chunk = chunks[missing[index]]
                if (!write(HandoffCharacteristic.WRITE, chunk.encode())) {
                    val resumed = recover()
                    if (resumed == null) {
                        return failure("reconnect budget exhausted", mtu = negotiatedMtu)
                    }
                    missing = resumed
                    index = 0
                    needRecovery = true
                    break
                }
                chunksSent++
                index++
            }
            if (needRecovery) {
                rounds++
                continue
            }

            write(HandoffCharacteristic.CONTROL, HandoffProtocol.encodeCommit(transferId))
            val statuses = drain()
            val commitOk = statuses.filterIsInstance<StatusMessage.CommitOk>().firstOrNull()
            if (commitOk != null) {
                link.on(LinkEvent.SyncCompleted)
                return HandoffResult(
                    success = true,
                    transferId = transferId,
                    frameBytes = commitOk.bytes,
                    bytesTransferred = bytes,
                    chunksSent = chunksSent,
                    retransmits = retransmits,
                    reconnectAttempts = attempts,
                    recoveryLatencyMs = recoveryMs,
                    durationMs = transport.nowMillis() - startMillis,
                    restarts = restarts,
                    crcAborts = crcAborts,
                    negotiatedMtu = negotiatedMtu,
                )
            }
            val abort = statuses.filterIsInstance<StatusMessage.Abort>().firstOrNull()
            if (abort != null) {
                if (abort.reason == AbortReason.CRC_MISMATCH) {
                    crcAborts++
                    if (restarts >= maxRestarts) {
                        return failure("crc abort after $restarts restarts", abort.reason, negotiatedMtu)
                    }
                    restarts++
                    transferId++
                    begun = false
                    missing = chunks.indices.toList()
                    rounds++
                    continue
                }
                return failure("commit aborted: ${abort.reason}", abort.reason, negotiatedMtu)
            }
            val retransmit = statuses.filterIsInstance<StatusMessage.Retransmit>().firstOrNull()
            if (retransmit != null) {
                retransmits += retransmit.missing.size
                missing = retransmit.missing
                rounds++
                continue
            }
            return failure("no commit response", mtu = negotiatedMtu)
        }
        return failure("commit rounds exhausted", mtu = negotiatedMtu)
    }
}
