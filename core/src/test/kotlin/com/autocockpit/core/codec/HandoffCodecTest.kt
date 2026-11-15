package com.autocockpit.core.codec

import com.autocockpit.core.BuildInfo
import com.autocockpit.core.TestPayloads
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.VehicleSignal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffCodecTest {

    @Test
    fun roundTripSingleTrack() {
        val payload = TestPayloads.payload(1)
        val frame = HandoffCodec.encode(payload)
        val decoded = HandoffCodec.decode(frame)
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(payload, (decoded as DecodeResult.Success).payload)
    }

    @Test
    fun roundTripFiveHundredTracks() {
        val payload = TestPayloads.payload(500)
        val frame = HandoffCodec.encode(payload)
        val decoded = HandoffCodec.decode(frame)
        assertTrue("decode failed: $decoded", decoded is DecodeResult.Success)
        assertEquals(payload, (decoded as DecodeResult.Success).payload)
    }

    @Test
    fun roundTripEveryTrackCountFromOneToFiveHundred() {
        for (count in 1..500 step 7) {
            val payload = TestPayloads.payload(count)
            val decoded = HandoffCodec.decode(HandoffCodec.encode(payload))
            assertTrue("count $count failed", decoded is DecodeResult.Success)
            assertEquals(payload, (decoded as DecodeResult.Success).payload)
        }
    }

    @Test
    fun propertyStyleRoundTripOverOneThousandRandomPayloads() {
        val rng = Random(20241115)
        val accents = listOf("teal", "amber", "violet", "crimson")
        repeat(1_000) { iteration ->
            val trackCount = rng.nextInt(0, 25)
            val tracks = (0 until trackCount).map { index ->
                com.autocockpit.core.domain.MediaTrack(
                    id = "r$iteration-$index",
                    title = "Random ${rng.nextInt(1_000_000)} ✓",
                    artist = "Artist ${rng.nextInt(500)}",
                    durationMs = 1L + rng.nextLong(900_000L),
                    uri = "synth://r/$iteration/$index?seed=${rng.nextLong()}",
                )
            }
            val payload = com.autocockpit.core.domain.HandoffPayload(
                profile = com.autocockpit.core.domain.DriverProfile(
                    driverId = "d-${rng.nextInt(1_000_000)}",
                    displayName = "Name ${rng.nextInt(10_000)}",
                    hvacSetpointC = 16f + rng.nextFloat() * 12f,
                    preferredVolume = rng.nextInt(101),
                    themeAccent = accents[rng.nextInt(accents.size)],
                ),
                queue = MediaQueue(tracks, position = if (tracks.isEmpty()) 0 else rng.nextInt(tracks.size)),
                generatedAtMillis = rng.nextLong(0, 2_000_000_000_000L),
                schemaVersion = BuildInfo.SCHEMA_VERSION,
            )
            val mtu = 8 + rng.nextInt(178)
            val chunks = Chunker(mtu).split(HandoffCodec.encode(payload))
            val reassembler = Reassembler()
            // Out-of-order arrival is legal.
            chunks.shuffled(rng).forEach { reassembler.offer(it) }
            val decoded = reassembler.reassemble()
            assertTrue("iteration $iteration mtu $mtu failed: $decoded", decoded is DecodeResult.Success)
            assertEquals(payload, (decoded as DecodeResult.Success).payload)
        }
    }

    @Test
    fun headerCarriesMagicSchemaLengthAndCrc() {
        val payload = TestPayloads.payload(3)
        val frame = HandoffCodec.encode(payload)
        val info = HandoffCodec.frameInfo(frame)
        assertEquals(0xAC, info.magic)
        assertEquals(BuildInfo.SCHEMA_VERSION, info.schemaVersion)
        assertEquals(frame.size - HandoffCodec.HEADER_BYTES, info.bodyBytes)
        assertEquals(Crc16.of(frame, HandoffCodec.HEADER_BYTES, frame.size), info.crc)
    }

    @Test
    fun crc16MatchesTheStandardCheckVector() {
        val check = "123456789".encodeToByteArray()
        assertEquals(0x29B1, Crc16.of(check))
    }

    @Test
    fun generationTimestampRoundTrips() {
        val payload = TestPayloads.payload(2)
        val decoded = HandoffCodec.decode(HandoffCodec.encode(payload))
        assertEquals(1_700_000_123_456L, (decoded as DecodeResult.Success).payload.generatedAtMillis)
    }

    @Test
    fun emptyFrameIsRejected() {
        val decoded = HandoffCodec.decode(ByteArray(0))
        assertEquals(DecodeFailure.EMPTY, (decoded as DecodeResult.Failure).reason)
    }

    @Test
    fun badMagicIsRejected() {
        val frame = HandoffCodec.encode(TestPayloads.payload(1))
        frame[0] = 0x00
        assertEquals(DecodeFailure.BAD_MAGIC, (HandoffCodec.decode(frame) as DecodeResult.Failure).reason)
    }

    @Test
    fun unsupportedSchemaIsRejected() {
        val frame = HandoffCodec.encode(TestPayloads.payload(1, schemaVersion = 9))
        assertEquals(
            DecodeFailure.UNSUPPORTED_SCHEMA,
            (HandoffCodec.decode(frame) as DecodeResult.Failure).reason,
        )
    }

    @Test
    fun truncatedFrameIsRejected() {
        val frame = HandoffCodec.encode(TestPayloads.payload(4))
        val truncated = frame.copyOf(frame.size - 5)
        assertEquals(
            DecodeFailure.LENGTH_MISMATCH,
            (HandoffCodec.decode(truncated) as DecodeResult.Failure).reason,
        )
    }

    @Test
    fun shorterThanHeaderIsRejected() {
        assertEquals(
            DecodeFailure.LENGTH_MISMATCH,
            (HandoffCodec.decode(ByteArray(3)) as DecodeResult.Failure).reason,
        )
    }

    @Test
    fun corruptedBodyByteFailsCrc() {
        val frame = HandoffCodec.encode(TestPayloads.payload(6))
        val corrupted = frame.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()
        val result = HandoffCodec.decode(corrupted)
        assertEquals(DecodeFailure.CRC_MISMATCH, (result as DecodeResult.Failure).reason)
    }

    @Test
    fun corruptedLengthFieldIsRejected() {
        val frame = HandoffCodec.encode(TestPayloads.payload(2))
        frame[3] = ((frame[3].toInt() + 1) and 0xFF).toByte()
        assertEquals(
            DecodeFailure.LENGTH_MISMATCH,
            (HandoffCodec.decode(frame) as DecodeResult.Failure).reason,
        )
    }

    @Test
    fun emptyProfileIsMalformedEvenWithValidCrc() {
        val body = TestPayloads.tlv(0x01, ByteArray(0)) + TestPayloads.tlv(0x02, ByteArray(0))
        val frame = TestPayloads.frameWithBody(body)
        assertEquals(
            DecodeFailure.MALFORMED,
            (HandoffCodec.decode(frame) as DecodeResult.Failure).reason,
        )
    }

    @Test
    fun positionOutsideQueueIsMalformed() {
        val valid = HandoffCodec.encode(TestPayloads.payload(1))
        val profileTlv = TestPayloads.topLevelField(valid, 0x01)!!
        val body = profileTlv + TestPayloads.tlv(0x02, TestPayloads.queueBody(position = 99, trackCount = 1))
        val decoded = HandoffCodec.decode(TestPayloads.frameWithBody(body))
        assertEquals(DecodeFailure.MALFORMED, (decoded as DecodeResult.Failure).reason)
    }

    @Test
    fun validQueueWithPositionRoundTripsThroughSplicedBody() {
        val valid = HandoffCodec.encode(TestPayloads.payload(1))
        val profileTlv = TestPayloads.topLevelField(valid, 0x01)!!
        val body = profileTlv + TestPayloads.tlv(0x02, TestPayloads.queueBody(position = 0, trackCount = 2))
        val decoded = HandoffCodec.decode(TestPayloads.frameWithBody(body))
        assertTrue("expected success, got $decoded", decoded is DecodeResult.Success)
        assertEquals(2, (decoded as DecodeResult.Success).payload.queue.tracks.size)
    }

    @Test
    fun unknownTopLevelFieldIsSkipped() {
        val frame = HandoffCodec.encode(TestPayloads.payload(2))
        val originalBody = frame.copyOfRange(HandoffCodec.HEADER_BYTES, frame.size)
        val extra = TestPayloads.tlv(0x7F, byteArrayOf(1, 2, 3))
        val rebuilt = TestPayloads.frameWithBody(originalBody + extra)
        val decoded = HandoffCodec.decode(rebuilt)
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(TestPayloads.payload(2).queue.tracks.size, (decoded as DecodeResult.Success).payload.queue.tracks.size)
    }

    @Test
    fun emptyQueueRoundTrips() {
        val payload = TestPayloads.payload(0)
        val decoded = HandoffCodec.decode(HandoffCodec.encode(payload))
        assertTrue(decoded is DecodeResult.Success)
        assertEquals(0, (decoded as DecodeResult.Success).payload.queue.tracks.size)
    }

    @Test
    fun encodingIsDeterministic() {
        val payload = TestPayloads.payload(12)
        val first = HandoffCodec.encode(payload)
        val second = HandoffCodec.encode(payload)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun differentPayloadsProduceDifferentFrames() {
        val a = HandoffCodec.encode(TestPayloads.payload(3))
        val b = HandoffCodec.encode(TestPayloads.payload(4))
        assertNotEquals(a.size, b.size)
    }

    @Test
    fun sampleModelsAreUsableIndependentlyOfAndroid() {
        // Guards the "no Android imports in :core" rule at the type level.
        val sample = SignalSample(VehicleSignal.Speed, 42.0, 1_000L, SampleStatus.AVAILABLE)
        assertEquals("speed", VehicleSignal.Speed.serializer().descriptor.serialName)
        assertEquals(42.0, sample.value, 0.0)
    }
}
