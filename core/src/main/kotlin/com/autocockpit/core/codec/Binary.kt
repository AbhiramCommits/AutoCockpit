package com.autocockpit.core.codec

/** Big-endian writer used by the handoff codec. Grows geometrically, never shrinks. */
internal class ByteWriter(initialCapacity: Int = 256) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    val length: Int get() = size

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var capacity = buffer.size
        while (capacity < size + extra) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }

    fun u8(value: Int) {
        ensure(1)
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun u16(value: Int) {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        ensure(2)
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun u32(value: Long) {
        require(value in 0..0xFFFFFFFFL) { "u32 out of range: $value" }
        ensure(4)
        buffer[size++] = ((value ushr 24) and 0xFF).toByte()
        buffer[size++] = ((value ushr 16) and 0xFF).toByte()
        buffer[size++] = ((value ushr 8) and 0xFF).toByte()
        buffer[size++] = (value and 0xFF).toByte()
    }

    fun f32(value: Float) {
        u32(value.toRawBits().toLong() and 0xFFFFFFFFL)
    }

    fun u64(value: Long) {
        require(value >= 0) { "u64 out of range: $value" }
        ensure(8)
        for (shift in 56 downTo 0 step 8) {
            buffer[size++] = ((value ushr shift) and 0xFF).toByte()
        }
    }

    fun bytes(value: ByteArray) {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }

    /** Length-prefixed UTF-8 string with a u16 length. */
    fun string(value: String) {
        val encoded = value.encodeToByteArray()
        require(encoded.size <= 0xFFFF) { "string too long for u16 prefix: ${encoded.size}" }
        u16(encoded.size)
        bytes(encoded)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    /** Direct access for writing a pre-sized body in place (used for the header). */
    fun raw(): ByteArray = buffer
}

/** Big-endian reader with bounds-checked accessors. */
internal class ByteReader(private val buffer: ByteArray, private val start: Int = 0, private val end: Int = buffer.size) {
    private var offset = start

    val position: Int get() = offset
    val remaining: Int get() = end - offset

    fun hasRemaining(): Boolean = offset < end

    private fun requireBytes(count: Int) {
        if (count < 0 || offset + count > end) {
            throw TruncatedFrameException("need $count bytes at $offset, have ${end - offset}")
        }
    }

    fun u8(): Int {
        requireBytes(1)
        return buffer[offset++].toInt() and 0xFF
    }

    fun u16(): Int {
        requireBytes(2)
        val value = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        offset += 2
        return value
    }

    fun u32(): Long {
        requireBytes(4)
        var value = 0L
        for (i in 0 until 4) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        offset += 4
        return value
    }

    fun f32(): Float = Float.fromBits(u32().toInt())

    fun u64(): Long {
        requireBytes(8)
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (buffer[offset + i].toLong() and 0xFF)
        }
        offset += 8
        return value
    }

    fun bytes(count: Int): ByteArray {
        requireBytes(count)
        val out = buffer.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    fun string(): String {
        val length = u16()
        return bytes(length).decodeToString()
    }

    /** Slice the remaining bytes without advancing. */
    fun rest(): ByteArray {
        val out = buffer.copyOfRange(offset, end)
        offset = end
        return out
    }

    fun subReader(length: Int): ByteReader {
        requireBytes(length)
        val sub = ByteReader(buffer, offset, offset + length)
        offset += length
        return sub
    }
}

internal class TruncatedFrameException(message: String) : Exception(message)

internal class MalformedFrameException(message: String) : Exception(message)

/** CRC-16/CCITT-FALSE: polynomial 0x1021, init 0xFFFF, no reflection, no final XOR. */
object Crc16 {
    private const val POLYNOMIAL = 0x1021
    private const val INITIAL = 0xFFFF

    fun of(data: ByteArray, fromIndex: Int = 0, toIndex: Int = data.size): Int {
        var crc = INITIAL
        for (i in fromIndex until toIndex) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) {
                    ((crc shl 1) xor POLYNOMIAL) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc and 0xFFFF
    }

    /** Residual check used by decoders: CRC over data + appended CRC equals 0xF0B8. */
    fun residual(data: ByteArray): Int = of(data)
}
