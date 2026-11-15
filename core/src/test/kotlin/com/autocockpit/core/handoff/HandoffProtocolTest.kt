package com.autocockpit.core.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffProtocolTest {

    @Test
    fun serviceAndCharacteristicUuidsAreFixed() {
        assertEquals("a11c0c70-7e11-4a01-9d00-000000000001", HandoffProtocol.SERVICE_UUID.toString())
        assertEquals("a11c0c70-7e11-4a01-9d00-000000000002", HandoffProtocol.WRITE_UUID.toString())
        assertEquals("a11c0c70-7e11-4a01-9d00-000000000003", HandoffProtocol.CONTROL_UUID.toString())
        assertEquals("a11c0c70-7e11-4a01-9d00-000000000004", HandoffProtocol.STATUS_UUID.toString())
    }

    @Test
    fun beginRoundTrips() {
        val encoded = HandoffProtocol.encodeBegin(42L, 1, 48_002, 3_001)
        val decoded = HandoffProtocol.decodeControl(encoded)
        assertEquals(ControlMessage.Begin(42L, 1, 48_002, 3_001), decoded)
        assertEquals(13, encoded.size)
    }

    @Test
    fun commitAbortAndResumeRoundTrip() {
        assertEquals(
            ControlMessage.Commit(7L),
            HandoffProtocol.decodeControl(HandoffProtocol.encodeCommit(7L)),
        )
        assertEquals(
            ControlMessage.Abort(9L, AbortReason.CRC_MISMATCH),
            HandoffProtocol.decodeControl(HandoffProtocol.encodeAbort(9L, AbortReason.CRC_MISMATCH)),
        )
        assertEquals(
            ControlMessage.Resume(11L),
            HandoffProtocol.decodeControl(HandoffProtocol.encodeResume(11L)),
        )
    }

    @Test
    fun unknownControlOpcodeIsReported() {
        val decoded = HandoffProtocol.decodeControl(byteArrayOf(0x7F))
        assertTrue(decoded is ControlMessage.Unknown)
    }

    @Test
    fun malformedControlDoesNotThrow() {
        val decoded = HandoffProtocol.decodeControl(byteArrayOf(0x01, 0x00))
        assertTrue(decoded is ControlMessage.Unknown)
    }

    @Test
    fun readyStatusRoundTrips() {
        val ready = StatusMessage.Ready(1, 185, 65_535)
        assertEquals(ready, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(ready)))
    }

    @Test
    fun progressAndRetransmitStatusesRoundTrip() {
        val progress = StatusMessage.Progress(3L, 120, 300)
        assertEquals(progress, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(progress)))

        val retransmit = StatusMessage.Retransmit(4L, listOf(1, 5, 9))
        assertEquals(retransmit, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(retransmit)))
    }

    @Test
    fun commitOkStatusCarriesIdempotencyFlag() {
        val first = StatusMessage.CommitOk(5L, 48_002, 7, alreadyCommitted = false)
        val repeated = StatusMessage.CommitOk(5L, 48_002, 7, alreadyCommitted = true)
        assertEquals(first, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(first)))
        assertEquals(repeated, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(repeated)))
    }

    @Test
    fun abortAndResumeInfoStatusesRoundTrip() {
        val abort = StatusMessage.Abort(6L, AbortReason.SCHEMA_MISMATCH)
        assertEquals(abort, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(abort)))
        val resume = StatusMessage.ResumeInfo(8L, 300, listOf(0, 1, 2))
        assertEquals(resume, HandoffProtocol.decodeStatus(HandoffProtocol.encodeStatus(resume)))
    }

    @Test
    fun unknownStatusReturnsNull() {
        assertTrue(HandoffProtocol.decodeStatus(byteArrayOf(0x7F)) == null)
        assertTrue(HandoffProtocol.decodeStatus(ByteArray(0)) == null)
    }

    @Test
    fun abortReasonCodesAreStable() {
        assertEquals(AbortReason.SCHEMA_MISMATCH, AbortReason.fromCode(1))
        assertEquals(AbortReason.CRC_MISMATCH, AbortReason.fromCode(3))
        assertEquals(AbortReason.PROTOCOL_ERROR, AbortReason.fromCode(99))
        assertNotNull(StatusMessage.Ready(1, 23, 100))
    }
}
