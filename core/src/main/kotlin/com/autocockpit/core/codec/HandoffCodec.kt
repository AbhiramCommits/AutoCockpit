package com.autocockpit.core.codec

import com.autocockpit.core.BuildInfo
import com.autocockpit.core.domain.DriverProfile
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.domain.SeatPosition

/** Why a frame could not be decoded. */
enum class DecodeFailure {
    EMPTY,
    BAD_MAGIC,
    UNSUPPORTED_SCHEMA,
    LENGTH_MISMATCH,
    CRC_MISMATCH,
    INCOMPLETE,
    MALFORMED,
    TOO_LARGE,
}

sealed interface DecodeResult {
    data class Success(
        val payload: HandoffPayload,
        val frameBytes: Int,
        val bodyBytes: Int,
        val schemaVersion: Int,
    ) : DecodeResult

    data class Failure(
        val reason: DecodeFailure,
        val detail: String,
    ) : DecodeResult
}

/**
 * Compact binary encoding of [HandoffPayload].
 *
 * Frame layout (6-byte header + body):
 * ```
 *  0     magic      0xAC ('AC' packed into one byte so the header stays 6 bytes)
 *  1     schema     1 byte
 *  2..3  length     body length, u16 big endian
 *  4..5  crc        CRC-16/CCITT-FALSE over the body only
 *  6..   body       TLV: tag u8, length u16 BE, value
 * ```
 * The body has three top-level TLVs: profile (0x01), queue (0x02), meta (0x03).
 * Every nested field is TLV as well, and unknown tags are skipped on decode, so a newer
 * peer can add fields without breaking an older reader.
 */
object HandoffCodec {
    const val MAGIC: Int = 0xAC
    const val HEADER_BYTES: Int = 6

    private const val TAG_PROFILE = 0x01
    private const val TAG_QUEUE = 0x02
    private const val TAG_META = 0x03

    private const val PROFILE_DRIVER_ID = 0x01
    private const val PROFILE_DISPLAY_NAME = 0x02
    private const val PROFILE_SEAT = 0x03
    private const val PROFILE_HVAC = 0x04
    private const val PROFILE_VOLUME = 0x05
    private const val PROFILE_ACCENT = 0x06

    private const val QUEUE_POSITION = 0x01
    private const val QUEUE_SOURCE = 0x02
    private const val QUEUE_TRACK = 0x03

    private const val TRACK_ID = 0x01
    private const val TRACK_TITLE = 0x02
    private const val TRACK_ARTIST = 0x03
    private const val TRACK_DURATION = 0x04
    private const val TRACK_URI = 0x05

    private const val META_GENERATED_AT = 0x01

    /** Encodes a payload into a complete, CRC-protected frame. */
    fun encode(payload: HandoffPayload): ByteArray {
        require(payload.schemaVersion in 0..255) { "schemaVersion must fit one byte: ${payload.schemaVersion}" }
        val body = encodeBody(payload)
        if (body.size > 0xFFFF) {
            throw IllegalArgumentException("payload body ${body.size} bytes exceeds the u16 frame limit")
        }
        val crc = Crc16.of(body)
        val frame = ByteArray(HEADER_BYTES + body.size)
        frame[0] = MAGIC.toByte()
        frame[1] = payload.schemaVersion.toByte()
        frame[2] = ((body.size ushr 8) and 0xFF).toByte()
        frame[3] = (body.size and 0xFF).toByte()
        frame[4] = ((crc ushr 8) and 0xFF).toByte()
        frame[5] = (crc and 0xFF).toByte()
        body.copyInto(frame, HEADER_BYTES)
        return frame
    }

    /** Renders the CRC and length fields of an encoded frame; used by tests and diagnostics. */
    fun frameInfo(frame: ByteArray): FrameInfo {
        require(frame.size >= HEADER_BYTES) { "frame shorter than header" }
        return FrameInfo(
            magic = frame[0].toInt() and 0xFF,
            schemaVersion = frame[1].toInt() and 0xFF,
            bodyBytes = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF),
            crc = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF),
        )
    }

    data class FrameInfo(
        val magic: Int,
        val schemaVersion: Int,
        val bodyBytes: Int,
        val crc: Int,
    )

    /** Decodes and validates a frame, never throwing. */
    fun decode(frame: ByteArray): DecodeResult {
        if (frame.isEmpty()) return DecodeResult.Failure(DecodeFailure.EMPTY, "empty frame")
        if (frame.size < HEADER_BYTES) {
            return DecodeResult.Failure(DecodeFailure.LENGTH_MISMATCH, "frame shorter than header: ${frame.size}")
        }
        val magic = frame[0].toInt() and 0xFF
        if (magic != MAGIC) {
            return DecodeResult.Failure(DecodeFailure.BAD_MAGIC, "magic 0x%02X".format(magic))
        }
        val schema = frame[1].toInt() and 0xFF
        if (schema != BuildInfo.SCHEMA_VERSION) {
            return DecodeResult.Failure(
                DecodeFailure.UNSUPPORTED_SCHEMA,
                "peer schema $schema, local ${BuildInfo.SCHEMA_VERSION}",
            )
        }
        val length = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        if (length != frame.size - HEADER_BYTES) {
            return DecodeResult.Failure(
                DecodeFailure.LENGTH_MISMATCH,
                "header says $length body bytes, frame carries ${frame.size - HEADER_BYTES}",
            )
        }
        val expectedCrc = ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
        val actualCrc = Crc16.of(frame, HEADER_BYTES, frame.size)
        if (expectedCrc != actualCrc) {
            return DecodeResult.Failure(
                DecodeFailure.CRC_MISMATCH,
                "crc 0x%04X, computed 0x%04X".format(expectedCrc, actualCrc),
            )
        }
        return try {
            val body = ByteReader(frame, HEADER_BYTES, frame.size)
            var profile: DriverProfile? = null
            var queue: MediaQueue? = null
            var generatedAtMillis = 0L
            while (body.hasRemaining()) {
                val tag = body.u8()
                val size = body.u16()
                val value = body.subReader(size)
                when (tag) {
                    TAG_PROFILE -> profile = readProfile(value)
                    TAG_QUEUE -> queue = readQueue(value)
                    TAG_META -> generatedAtMillis = readMeta(value)
                    else -> Unit // forward compatible: skip unknown fields
                }
            }
            if (profile == null) return DecodeResult.Failure(DecodeFailure.MALFORMED, "missing profile")
            if (queue == null) return DecodeResult.Failure(DecodeFailure.MALFORMED, "missing queue")
            DecodeResult.Success(
                payload = HandoffPayload(profile, queue, generatedAtMillis, schemaVersion = schema),
                frameBytes = frame.size,
                bodyBytes = length,
                schemaVersion = schema,
            )
        } catch (e: TruncatedFrameException) {
            DecodeResult.Failure(DecodeFailure.LENGTH_MISMATCH, e.message ?: "truncated")
        } catch (e: MalformedFrameException) {
            DecodeResult.Failure(DecodeFailure.MALFORMED, e.message ?: "malformed")
        } catch (e: IllegalArgumentException) {
            DecodeResult.Failure(DecodeFailure.MALFORMED, e.message ?: "malformed")
        }
    }

    private fun encodeBody(payload: HandoffPayload): ByteArray {
        val writer = ByteWriter(512)
        writer.tlv(TAG_PROFILE, encodeProfile(payload.profile))
        writer.tlv(TAG_QUEUE, encodeQueue(payload.queue))
        val meta = ByteWriter(12)
        meta.field(META_GENERATED_AT) { it.u64(payload.generatedAtMillis) }
        writer.tlv(TAG_META, meta.toByteArray())
        return writer.toByteArray()
    }

    private fun ByteWriter.tlv(tag: Int, value: ByteArray) {
        u8(tag)
        u16(value.size)
        bytes(value)
    }

    private inline fun ByteWriter.field(tag: Int, write: (ByteWriter) -> Unit) {
        val inner = ByteWriter(32)
        write(inner)
        tlv(tag, inner.toByteArray())
    }

    private fun encodeProfile(profile: DriverProfile): ByteArray {
        val writer = ByteWriter(96)
        writer.field(PROFILE_DRIVER_ID) { it.string(profile.driverId) }
        writer.field(PROFILE_DISPLAY_NAME) { it.string(profile.displayName) }
        writer.field(PROFILE_SEAT) { it.u8(profile.seat.ordinal) }
        writer.field(PROFILE_HVAC) { it.f32(profile.hvacSetpointC) }
        writer.field(PROFILE_VOLUME) { it.u8(profile.preferredVolume) }
        writer.field(PROFILE_ACCENT) { it.string(profile.themeAccent) }
        return writer.toByteArray()
    }

    private fun encodeQueue(queue: MediaQueue): ByteArray {
        val writer = ByteWriter(128)
        writer.field(QUEUE_POSITION) { it.u16(queue.position) }
        writer.field(QUEUE_SOURCE) { it.string(queue.sourceLabel) }
        for (track in queue.tracks) {
            writer.tlv(QUEUE_TRACK, encodeTrack(track))
        }
        return writer.toByteArray()
    }

    private fun encodeTrack(track: MediaTrack): ByteArray {
        val writer = ByteWriter(96)
        writer.field(TRACK_ID) { it.string(track.id) }
        writer.field(TRACK_TITLE) { it.string(track.title) }
        writer.field(TRACK_ARTIST) { it.string(track.artist) }
        writer.field(TRACK_DURATION) { it.u32(track.durationMs) }
        writer.field(TRACK_URI) { it.string(track.uri) }
        return writer.toByteArray()
    }

    private fun readMeta(reader: ByteReader): Long {
        var generatedAt = 0L
        while (reader.hasRemaining()) {
            val tag = reader.u8()
            val size = reader.u16()
            val value = reader.subReader(size)
            when (tag) {
                META_GENERATED_AT -> generatedAt = value.u64()
                else -> Unit
            }
        }
        return generatedAt
    }

    private fun readProfile(reader: ByteReader): DriverProfile {
        var driverId: String? = null
        var displayName: String? = null
        var seat = SeatPosition.DRIVER
        var hvac = 21.5f
        var volume = 40
        var accent = "teal"
        val seen = HashSet<Int>()
        while (reader.hasRemaining()) {
            val tag = reader.u8()
            val size = reader.u16()
            val value = reader.subReader(size)
            val known = when (tag) {
                PROFILE_DRIVER_ID, PROFILE_DISPLAY_NAME, PROFILE_SEAT,
                PROFILE_HVAC, PROFILE_VOLUME, PROFILE_ACCENT,
                -> true
                else -> false
            }
            if (known && !seen.add(tag)) {
                throw MalformedFrameException("duplicate profile field tag $tag")
            }
            when (tag) {
                PROFILE_DRIVER_ID -> driverId = value.string()
                PROFILE_DISPLAY_NAME -> displayName = value.string()
                PROFILE_SEAT -> {
                    val ordinal = value.u8()
                    seat = SeatPosition.entries.getOrNull(ordinal)
                        ?: throw MalformedFrameException("bad seat ordinal $ordinal")
                }
                PROFILE_HVAC -> hvac = value.f32()
                PROFILE_VOLUME -> volume = value.u8()
                PROFILE_ACCENT -> accent = value.string()
                else -> Unit
            }
        }
        return DriverProfile(
            driverId = driverId ?: throw MalformedFrameException("missing driverId"),
            displayName = displayName ?: throw MalformedFrameException("missing displayName"),
            seat = seat,
            hvacSetpointC = hvac,
            preferredVolume = volume,
            themeAccent = accent,
        )
    }

    private fun readQueue(reader: ByteReader): MediaQueue {
        var position = 0
        var source = "phone"
        val tracks = ArrayList<MediaTrack>()
        while (reader.hasRemaining()) {
            val tag = reader.u8()
            val size = reader.u16()
            val value = reader.subReader(size)
            when (tag) {
                QUEUE_POSITION -> position = value.u16()
                QUEUE_SOURCE -> source = value.string()
                QUEUE_TRACK -> tracks += readTrack(value)
                else -> Unit
            }
        }
        val safePosition = if (tracks.isEmpty()) 0 else position
        if (tracks.isNotEmpty() && position >= tracks.size) {
            throw MalformedFrameException("position $position outside ${tracks.size} tracks")
        }
        return MediaQueue(tracks, safePosition, source)
    }

    private fun readTrack(reader: ByteReader): MediaTrack {
        var id: String? = null
        var title: String? = null
        var artist: String? = null
        var duration = 0L
        var uri: String? = null
        while (reader.hasRemaining()) {
            val tag = reader.u8()
            val size = reader.u16()
            val value = reader.subReader(size)
            when (tag) {
                TRACK_ID -> id = value.string()
                TRACK_TITLE -> title = value.string()
                TRACK_ARTIST -> artist = value.string()
                TRACK_DURATION -> duration = value.u32()
                TRACK_URI -> uri = value.string()
                else -> Unit
            }
        }
        return MediaTrack(
            id = id ?: throw MalformedFrameException("missing track id"),
            title = title ?: throw MalformedFrameException("missing track title"),
            artist = artist ?: throw MalformedFrameException("missing track artist"),
            durationMs = duration,
            uri = uri ?: throw MalformedFrameException("missing track uri"),
        )
    }
}
