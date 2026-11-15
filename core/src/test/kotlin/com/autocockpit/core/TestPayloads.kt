package com.autocockpit.core

import com.autocockpit.core.codec.ByteWriter
import com.autocockpit.core.codec.Crc16
import com.autocockpit.core.codec.HandoffCodec
import com.autocockpit.core.domain.DriverProfile
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.domain.SeatPosition

object TestPayloads {
    fun track(index: Int): MediaTrack = MediaTrack(
        id = "track-$index",
        title = "Test Track $index",
        artist = "Fixture Artist",
        durationMs = 180_000L + index,
        uri = "synth://test/$index",
    )

    fun payload(trackCount: Int, schemaVersion: Int = BuildInfo.SCHEMA_VERSION): HandoffPayload {
        val tracks = (0 until trackCount).map(::track)
        return HandoffPayload(
            profile = DriverProfile(
                driverId = "driver-test",
                displayName = "Test Driver",
                seat = SeatPosition.DRIVER,
                hvacSetpointC = 22.5f,
                preferredVolume = 55,
                themeAccent = "teal",
            ),
            queue = MediaQueue(tracks, position = if (tracks.isEmpty()) 0 else 0, sourceLabel = "unit-test"),
            generatedAtMillis = 1_700_000_123_456L,
            schemaVersion = schemaVersion,
        )
    }

    /** Wraps an arbitrary body in a valid header with a correct CRC. */
    fun frameWithBody(body: ByteArray, schemaVersion: Int = BuildInfo.SCHEMA_VERSION): ByteArray {
        val crc = Crc16.of(body)
        val frame = ByteArray(HandoffCodec.HEADER_BYTES + body.size)
        frame[0] = HandoffCodec.MAGIC.toByte()
        frame[1] = schemaVersion.toByte()
        frame[2] = ((body.size ushr 8) and 0xFF).toByte()
        frame[3] = (body.size and 0xFF).toByte()
        frame[4] = ((crc ushr 8) and 0xFF).toByte()
        frame[5] = (crc and 0xFF).toByte()
        body.copyInto(frame, HandoffCodec.HEADER_BYTES)
        return frame
    }

    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val writer = ByteWriter(value.size + 3)
        writer.u8(tag)
        writer.u16(value.size)
        writer.bytes(value)
        return writer.toByteArray()
    }

    fun u16Bytes(value: Int): ByteArray = ByteWriter(2).apply { u16(value) }.toByteArray()

    fun utf8(value: String): ByteArray = ByteWriter(value.length + 2).apply { string(value) }.toByteArray()

    fun trackBody(index: Int): ByteArray = ByteWriter(64).apply {
        bytes(tlv(0x01, utf8("t$index")))
        bytes(tlv(0x02, utf8("Title $index")))
        bytes(tlv(0x03, utf8("Artist")))
        bytes(tlv(0x04, ByteWriter(4).apply { u32(60_000L + index) }.toByteArray()))
        bytes(tlv(0x05, utf8("synth://t/$index")))
    }.toByteArray()

    /** A queue body with an arbitrary (possibly illegal) playhead. */
    fun queueBody(position: Int, trackCount: Int): ByteArray = ByteWriter(128).apply {
        bytes(tlv(0x01, u16Bytes(position)))
        bytes(tlv(0x02, utf8("fixture")))
        repeat(trackCount) { bytes(tlv(0x03, trackBody(it))) }
    }.toByteArray()

    /** Extract the first top-level TLV with [tag] from an encoded frame, re-framed. */
    fun topLevelField(frame: ByteArray, tag: Int): ByteArray? {
        val reader = com.autocockpit.core.codec.ByteReader(frame, HandoffCodec.HEADER_BYTES, frame.size)
        while (reader.hasRemaining()) {
            val fieldTag = reader.u8()
            val length = reader.u16()
            val value = reader.bytes(length)
            if (fieldTag == tag) return tlv(tag, value)
        }
        return null
    }
}
