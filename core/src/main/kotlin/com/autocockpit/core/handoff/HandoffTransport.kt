package com.autocockpit.core.handoff

/** Virtual clock used by the loopback transports so latency is measured deterministically. */
class SimulatedClock(var now: Long = 1_700_000_000_000L) {
    fun advance(millis: Long) {
        now += millis
    }

    fun nowMillis(): Long = now
}

/**
 * Transport abstraction for the companion handoff. Implementations move opaque characteristic
 * values; the protocol lives in [CompanionClient] and [HandoffServerCore]. This is what makes the
 * whole handoff runnable headless (loopback/lossy) as well as over real BLE.
 */
interface HandoffTransport {
    val name: String
    val connected: Boolean

    /** Current ATT payload size in bytes (MTU - 3). */
    val mtu: Int

    fun connect(): Boolean

    /** Requests [requestedMtu] and returns the negotiated MTU (>= 23). */
    fun negotiateMtu(requestedMtu: Int): Int

    fun writeCharacteristic(characteristic: HandoffCharacteristic, value: ByteArray): Boolean

    /** Drains queued notifications from the head unit. */
    fun notifications(): List<ByteArray>

    fun disconnect()

    fun nowMillis(): Long

    /** Advances simulated/radio time when a write is dropped before it reaches the delegate. */
    fun idleFor(millis: Long) = Unit
}

/**
 * In-memory, loss-free transport wired directly to a [HandoffServerCore]. Every write advances a
 * virtual clock (2 ms per characteristic value, 120 ms per connect, 40 ms per MTU negotiation), so
 * recovery latency reported by the simulation is real measured protocol time, not wall clock.
 */
class LoopbackTransport(
    private val server: HandoffServerCore,
    private val clock: SimulatedClock = SimulatedClock(),
) : HandoffTransport {

    override val name: String = "loopback"
    private val statusQueue = ArrayDeque<ByteArray>()
    private var linkUp = false
    private var payloadMtu = HandoffProtocol.DEFAULT_MTU - HandoffProtocol.ATT_HEADER_BYTES

    override val connected: Boolean get() = linkUp
    override val mtu: Int get() = payloadMtu

    override fun connect(): Boolean {
        linkUp = true
        clock.advance(CONNECT_LATENCY_MS)
        return true
    }

    override fun negotiateMtu(requestedMtu: Int): Int {
        if (!linkUp) return 0
        clock.advance(MTU_LATENCY_MS)
        val negotiated = requestedMtu.coerceIn(HandoffProtocol.DEFAULT_MTU, HandoffProtocol.MAX_MTU)
        payloadMtu = negotiated - HandoffProtocol.ATT_HEADER_BYTES
        return negotiated
    }

    override fun writeCharacteristic(characteristic: HandoffCharacteristic, value: ByteArray): Boolean {
        if (!linkUp) return false
        clock.advance(WRITE_LATENCY_MS)
        when (characteristic) {
            HandoffCharacteristic.WRITE -> server.onChunkRaw(value).forEach { statusQueue += HandoffProtocol.encodeStatus(it) }
            HandoffCharacteristic.CONTROL -> server.onControl(value).forEach { statusQueue += HandoffProtocol.encodeStatus(it) }
            HandoffCharacteristic.STATUS -> Unit
        }
        return true
    }

    override fun notifications(): List<ByteArray> {
        if (!linkUp) return emptyList()
        val out = statusQueue.toList()
        statusQueue.clear()
        return out
    }

    override fun disconnect() {
        linkUp = false
        statusQueue.clear()
    }

    override fun nowMillis(): Long = clock.nowMillis()

    override fun idleFor(millis: Long) {
        clock.advance(millis)
    }

    companion object {
        const val CONNECT_LATENCY_MS: Long = 120L
        const val MTU_LATENCY_MS: Long = 40L
        const val WRITE_LATENCY_MS: Long = 2L
    }
}

/**
 * Wraps a transport and injects the failures a real 2.4 GHz link produces: dropped chunk writes,
 * corrupted chunk payloads (detected by the frame CRC at COMMIT) and a mid-transfer disconnect.
 */
class LossyTransport(
    private val delegate: HandoffTransport,
    private val dropRate: Double = 0.0,
    private val corruptRate: Double = 0.0,
    private val disconnectAfterChunks: Int = Int.MAX_VALUE,
    seed: Long = 0L,
    /**
     * Real BLE rejects CRC-failed packets at the link layer, so by default a corrupted chunk is
     * never delivered (it behaves like a loss and is retransmitted). Set this to deliver the
     * corrupted bytes so the protocol-level frame CRC path can be exercised.
     */
    private val deliverCorrupted: Boolean = false,
) : HandoffTransport by delegate {

    private val random = kotlin.random.Random(seed)
    private var chunkWrites = 0

    var droppedChunks: Int = 0
        private set
    var corruptedChunks: Int = 0
        private set
    var injectedDisconnects: Int = 0
        private set

    override fun writeCharacteristic(characteristic: HandoffCharacteristic, value: ByteArray): Boolean {
        if (characteristic != HandoffCharacteristic.WRITE) {
            return delegate.writeCharacteristic(characteristic, value)
        }
        chunkWrites++
        if (chunkWrites == disconnectAfterChunks) {
            injectedDisconnects++
            delegate.disconnect()
            return false
        }
        if (random.nextDouble() < dropRate) {
            droppedChunks++
            delegate.idleFor(2L)
            return true // write-without-response succeeds on the radio; the payload never arrives
        }
        if (random.nextDouble() < corruptRate) {
            corruptedChunks++
            if (!deliverCorrupted) {
                delegate.idleFor(2L)
                return true
            }
            val corrupted = value.copyOf()
            corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 0x5A).toByte()
            return delegate.writeCharacteristic(characteristic, corrupted)
        }
        return delegate.writeCharacteristic(characteristic, value)
    }
}
