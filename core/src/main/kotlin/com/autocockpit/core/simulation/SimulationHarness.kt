package com.autocockpit.core.simulation

import com.autocockpit.core.BuildInfo
import com.autocockpit.core.codec.Chunker
import com.autocockpit.core.codec.HandoffCodec
import com.autocockpit.core.domain.DriverProfile
import com.autocockpit.core.domain.HandoffPayload
import com.autocockpit.core.domain.MediaQueue
import com.autocockpit.core.domain.MediaTrack
import com.autocockpit.core.domain.SampleStatus
import com.autocockpit.core.domain.SeatPosition
import com.autocockpit.core.domain.SignalSample
import com.autocockpit.core.domain.UiTier
import com.autocockpit.core.domain.VehicleSignal
import com.autocockpit.core.handoff.CompanionClient
import com.autocockpit.core.handoff.HandoffProtocol
import com.autocockpit.core.handoff.HandoffRepository
import com.autocockpit.core.handoff.HandoffServerCore
import com.autocockpit.core.handoff.LoopbackTransport
import com.autocockpit.core.handoff.LossyTransport
import com.autocockpit.core.handoff.SimulatedClock
import com.autocockpit.core.health.DegradationCounters
import com.autocockpit.core.health.DegradationPolicy
import com.autocockpit.core.health.PropertyHealthMonitor
import com.autocockpit.core.link.BackoffPolicy
import com.autocockpit.core.link.LinkStateMachine
import kotlin.math.sin
import kotlin.random.Random

/** Deterministic payload generator shared by the simulation, tests and the docs. */
object SyntheticPayloads {
    private val artists = listOf("Synth Unit 7", "Oscillator Lab", "Chassis Tone", "Voltage Bloom", "AI Driver")

    fun queue(trackCount: Int, seed: Long = 0L): MediaQueue {
        require(trackCount >= 0) { "trackCount must be >= 0" }
        val tracks = (0 until trackCount).map { i ->
            MediaTrack(
                id = "t%04d".format(i + 1),
                title = "Synthetic Track %04d".format(i + 1),
                artist = artists[i % artists.size],
                durationMs = 120_000L + (i % 40) * 1_500L,
                uri = "synth://tone/${220 + (i % 12) * 55}?i=$i&seed=$seed",
            )
        }
        return MediaQueue(tracks, position = if (tracks.isEmpty()) 0 else tracks.size / 2, sourceLabel = "simulated-phone")
    }

    fun canonical(trackCount: Int, generatedAtMillis: Long = 1_700_000_000_000L): HandoffPayload = HandoffPayload(
        profile = DriverProfile(
            driverId = "driver-0001",
            displayName = "Simulated Driver",
            seat = SeatPosition.DRIVER,
            hvacSetpointC = 21.5f,
            preferredVolume = 42,
            themeAccent = "teal",
        ),
        queue = queue(trackCount),
        generatedAtMillis = generatedAtMillis,
        schemaVersion = BuildInfo.SCHEMA_VERSION,
    )

    fun random(rng: Random, trackCount: Int, generatedAtMillis: Long): HandoffPayload {
        val seats = SeatPosition.entries
        return HandoffPayload(
            profile = DriverProfile(
                driverId = "driver-${rng.nextInt(100_000).toString().padStart(5, '0')}",
                displayName = "Driver ${rng.nextInt(1000)}",
                seat = seats[rng.nextInt(seats.size)],
                hvacSetpointC = 17.0f + rng.nextInt(110) / 10.0f,
                preferredVolume = rng.nextInt(101),
                themeAccent = listOf("teal", "amber", "violet").random(rng),
            ),
            queue = queue(trackCount, seed = rng.nextLong()),
            generatedAtMillis = generatedAtMillis,
            schemaVersion = BuildInfo.SCHEMA_VERSION,
        )
    }
}

/** Tunables for [SimulationHarness]. All stochastic choices come from one seeded RNG. */
data class SimulationConfig(
    val sessionsPerLossRate: Int = 500,
    val seed: Long = 42L,
    /** Requested ATT MTU for the handoff channel (the real client uses 185). */
    val mtu: Int = HandoffProtocol.REQUESTED_MTU,
    val lossRates: List<Double> = listOf(0.0, 0.01, 0.05, 0.10),
    val maxTracks: Int = 500,
    val bleDisconnectRate: Double = 0.25,
    val crcCorruptionRate: Double = 0.01,
    val propertyDropoutProbability: Double = 0.35,
    val staleProbability: Double = 0.12,
    val simulatedFrameMillis: Long = 100L,
    val simulatedFramesPerSession: Int = 120,
    val epochMillis: Long = 1_700_000_000_000L,
    val backoff: BackoffPolicy = BackoffPolicy(),
) {
    init {
        require(sessionsPerLossRate > 0)
        require(lossRates.isNotEmpty())
        require(lossRates.all { it in 0.0..1.0 })
        require(mtu in HandoffProtocol.DEFAULT_MTU..HandoffProtocol.MAX_MTU)
        require(maxTracks in 1..5000)
    }
}

private class FaultTally {
    var propertyDropouts = 0
    var staleSamples = 0
    var propertyErrors = 0
    var bleDisconnects = 0
    var chunkLosses = 0
    var crcCorruptions = 0
    var duplicateChunks = 0
    var outOfOrderChunks = 0
    var reconnectExhaustions = 0
    var schemaAborts = 0
    var crcRestarts = 0
}

private data class DegradationOutcome(
    val counters: DegradationCounters,
    val recoveryLatenciesMs: List<Long>,
)

/**
 * Headless, deterministic closed-loop simulation of the AutoCockpit data paths.
 *
 * The handoff is not modelled abstractly: every session runs the real [CompanionClient] against a
 * real [HandoffServerCore] through [LossyTransport] on top of [LoopbackTransport]. Chunk drops,
 * corrupted frames and mid-transfer disconnects therefore exercise the production codec, chunker,
 * CRC, retransmit, resume and idempotent-commit paths. In parallel a [PropertyHealthMonitor] is
 * driven through dropouts, stale samples and error bursts so the report can show how many signal
 * frames the cockpit serves as LIVE, LAST_KNOWN or UNAVAILABLE.
 *
 * The same seed and config always produce byte-identical JSON: the clock is a simulated epoch,
 * never the wall clock.
 */
class SimulationHarness(private val config: SimulationConfig = SimulationConfig()) {

    fun run(): SimulationReport {
        val faults = FaultTally()
        val repository = HandoffRepository()
        val server = HandoffServerCore(repository)

        // Payload statistics are always reported at the 20-byte ATT payload (MTU 23).
        val payloadChunker = Chunker(BuildInfo.DEFAULT_ATT_PAYLOAD_BYTES)
        val maxPayload = SyntheticPayloads.canonical(500)
        val maxFrame = HandoffCodec.encode(maxPayload)
        val maxChunks = payloadChunker.split(maxFrame)

        val allRetransmits = ArrayList<Long>()
        val allAttempts = ArrayList<Long>()
        val allRecovery = ArrayList<Long>()
        var totalSessions = 0
        var totalSucceeded = 0
        var sessionsWithReconnect = 0
        var maxFrameBytesSeen = maxFrame.size

        val degradationCounters = ArrayList<DegradationCounters>()
        val degradationRecovery = ArrayList<Long>()
        val byRate = ArrayList<LossRateHandoff>()

        config.lossRates.forEachIndexed { rateIndex, lossRate ->
            val rng = Random(config.seed * 31 + rateIndex * 1_000_003L)
            val rateRetransmits = ArrayList<Long>()
            val rateAttempts = ArrayList<Long>()
            val rateRecovery = ArrayList<Long>()
            val rateBytes = ArrayList<Long>()
            var succeeded = 0
            var reconnected = 0

            repeat(config.sessionsPerLossRate) { session ->
                val trackCount = 1 + rng.nextInt(config.maxTracks)
                val generatedAt = config.epochMillis + session * 1_000L
                val payload = SyntheticPayloads.random(rng, trackCount, generatedAt)
                val frame = HandoffCodec.encode(payload)
                if (frame.size > maxFrameBytesSeen) maxFrameBytesSeen = frame.size

                val clock = SimulatedClock(config.epochMillis)
                val loopback = LoopbackTransport(server, clock)
                val expectedChunks = Chunker(config.mtu - HandoffProtocol.ATT_HEADER_BYTES)
                    .split(frame).size
                val disconnectAfter = if (rng.nextDouble() < config.bleDisconnectRate) {
                    1 + rng.nextInt(expectedChunks.coerceAtLeast(1))
                } else {
                    Int.MAX_VALUE
                }
                val transport = LossyTransport(
                    delegate = loopback,
                    dropRate = lossRate,
                    corruptRate = config.crcCorruptionRate,
                    disconnectAfterChunks = disconnectAfter,
                    seed = rng.nextLong(),
                )
                val client = CompanionClient(
                    transport = transport,
                    link = LinkStateMachine(config.backoff, Random(rng.nextLong())),
                    requestedMtu = config.mtu,
                )
                val transferId = 1L + rateIndex * 1_000_000L + session
                val result = client.send(payload, transferId)

                faults.chunkLosses += transport.droppedChunks
                faults.crcCorruptions += transport.corruptedChunks
                faults.bleDisconnects += transport.injectedDisconnects
                faults.crcRestarts += result.restarts
                if (result.failureReason?.contains("reconnect budget") == true) faults.reconnectExhaustions++

                val success = result.success && repository.current() == payload
                if (success) succeeded++
                if (result.reconnectAttempts > 0) reconnected++
                rateRetransmits += result.retransmits.toLong()
                rateBytes += result.bytesTransferred.toLong()
                if (result.reconnectAttempts > 0) {
                    rateAttempts += result.reconnectAttempts.toLong()
                    rateRecovery += result.recoveryLatencyMs
                }

                val degradation = simulateDegradation(rng, faults)
                degradationCounters += degradation.counters
                degradationRecovery += degradation.recoveryLatenciesMs
            }

            faults.duplicateChunks = server.duplicateChunks
            faults.outOfOrderChunks = server.outOfOrderChunks
            faults.schemaAborts = server.schemaAborts

            totalSessions += config.sessionsPerLossRate
            totalSucceeded += succeeded
            sessionsWithReconnect += reconnected
            allRetransmits += rateRetransmits
            allAttempts += rateAttempts
            allRecovery += rateRecovery

            byRate += LossRateHandoff(
                lossRate = lossRate,
                sessions = config.sessionsPerLossRate,
                succeeded = succeeded,
                successRate = Stats.round2(succeeded * 100.0 / config.sessionsPerLossRate),
                reconnectSessions = reconnected,
                retransmits = Stats.summary(rateRetransmits),
                reconnectAttempts = Stats.summary(rateAttempts),
                recoveryLatencyMs = Stats.summary(rateRecovery),
                bytesOnWire = Stats.summary(rateBytes),
            )
        }

        val totalFrames = degradationCounters.sumOf { it.framesObserved }
        val totalLive = degradationCounters.sumOf { it.liveFrames }
        val totalLastKnown = degradationCounters.sumOf { it.lastKnownFrames }
        val totalUnavailable = degradationCounters.sumOf { it.unavailableFrames }

        return SimulationReport(
            build = BuildSection(
                app = BuildInfo.APP_NAME,
                version = BuildInfo.VERSION,
                schemaVersion = BuildInfo.SCHEMA_VERSION,
                jvm = System.getProperty("java.version") ?: "unknown",
                os = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}",
            ),
            config = ConfigSection(
                seed = config.seed,
                sessionsPerLossRate = config.sessionsPerLossRate,
                totalSessions = totalSessions,
                lossRates = config.lossRates,
                mtu = config.mtu,
                chunkPayloadBytes = config.mtu - HandoffProtocol.ATT_HEADER_BYTES - Chunker.CHUNK_HEADER_BYTES,
                tracksPerSession = "1..${config.maxTracks} uniform",
                staleAfterMillis = DegradationPolicy().staleAfterMillis,
                unavailableAfterMillis = DegradationPolicy().unavailableAfterMillis,
                simulatedClockEpochMillis = config.epochMillis,
            ),
            faults = FaultCounters(
                propertyDropouts = faults.propertyDropouts,
                staleSamples = faults.staleSamples,
                propertyErrors = faults.propertyErrors,
                bleDisconnects = faults.bleDisconnects,
                chunkLosses = faults.chunkLosses,
                crcCorruptions = faults.crcCorruptions,
                duplicateChunks = faults.duplicateChunks,
                outOfOrderChunks = faults.outOfOrderChunks,
                reconnectExhaustions = faults.reconnectExhaustions,
                schemaAborts = faults.schemaAborts,
            ),
            handoff = HandoffSection(
                sessions = totalSessions,
                succeeded = totalSucceeded,
                successRate = Stats.round2(totalSucceeded * 100.0 / totalSessions.coerceAtLeast(1)),
                meanReconnectAttempts = Stats.summary(allAttempts),
                meanRecoveryLatencyMs = Stats.summary(allRecovery),
                chunkRetransmits = Stats.summary(allRetransmits),
                sessionsWithReconnect = sessionsWithReconnect,
                byLossRate = byRate,
                maxPayloadBytes = maxFrameBytesSeen,
                maxPayloadTracks = 500,
                bytesOnWire500Tracks = maxFrame.size +
                    maxChunks.size * (Chunker.CHUNK_HEADER_BYTES + HandoffProtocol.ATT_HEADER_BYTES),
                chunks500Tracks = maxChunks.size,
                frameHeaderBytes = HandoffCodec.HEADER_BYTES,
                chunkHeaderBytes = Chunker.CHUNK_HEADER_BYTES,
            ),
            degradation = DegradationSection(
                sessions = totalSessions,
                framesObserved = totalFrames,
                liveFrames = totalLive,
                lastKnownFrames = totalLastKnown,
                unavailableFrames = totalUnavailable,
                livePct = if (totalFrames == 0) 0.0 else Stats.round2(totalLive * 100.0 / totalFrames),
                lastKnownPct = if (totalFrames == 0) 0.0 else Stats.round2(totalLastKnown * 100.0 / totalFrames),
                unavailablePct = if (totalFrames == 0) 0.0 else Stats.round2(totalUnavailable * 100.0 / totalFrames),
                recoveryLatencyMs = Stats.summary(degradationRecovery),
            ),
            payload = PayloadSection(
                oneTrackBytes = HandoffCodec.encode(SyntheticPayloads.canonical(1)).size,
                tenTrackBytes = HandoffCodec.encode(SyntheticPayloads.canonical(10)).size,
                hundredTrackBytes = HandoffCodec.encode(SyntheticPayloads.canonical(100)).size,
                fiveHundredTrackBytes = maxFrame.size,
                fiveHundredTrackChunksAt20 = maxChunks.size,
            ),
        )
    }

    /** Drives the health monitor through a 12-second synthetic drive with dropouts and stale data. */
    private fun simulateDegradation(rng: Random, faults: FaultTally): DegradationOutcome {
        val policy = DegradationPolicy()
        val monitor = PropertyHealthMonitor(policy)
        val signals = VehicleSignal.all
        val frameMs = config.simulatedFrameMillis
        val frames = config.simulatedFramesPerSession

        data class Window(val signal: VehicleSignal, val startFrame: Int, val endFrame: Int)
        val windows = ArrayList<Window>()
        for (signal in signals) {
            if (rng.nextDouble() < config.propertyDropoutProbability) {
                val start = rng.nextInt(frames / 2)
                val length = 5 + rng.nextInt(15)
                val end = (start + length).coerceAtMost(frames - 1)
                windows += Window(signal, start, end)
                faults.propertyDropouts++
            }
        }
        val errorSignal = signals[rng.nextInt(signals.size)]
        val errorStart = frames / 3 + rng.nextInt(frames / 2)
        val recovering = HashMap<VehicleSignal, Long>()
        val recoveries = ArrayList<Long>()
        var now = config.epochMillis

        for (frame in 0 until frames) {
            now += frameMs
            for (signal in signals) {
                val window = windows.firstOrNull { it.signal == signal && frame in it.startFrame..it.endFrame }
                val errorBurst = window == null && signal == errorSignal && frame in errorStart until (errorStart + 3)
                val stale = window == null && !errorBurst && rng.nextDouble() < config.staleProbability
                val status = when {
                    window != null -> SampleStatus.UNAVAILABLE
                    errorBurst -> SampleStatus.ERROR
                    stale -> SampleStatus.STALE
                    else -> SampleStatus.AVAILABLE
                }
                if (stale) faults.staleSamples++
                if (errorBurst) faults.propertyErrors++
                if (window != null) {
                    recovering.putIfAbsent(signal, config.epochMillis + (window.endFrame + 1) * frameMs)
                }
                monitor.onSample(SignalSample(signal, rawValue(signal, frame), now, status))
            }
            val views = monitor.observeFrame(now)
            for (signal in signals) {
                val due = recovering[signal] ?: continue
                if (views.getValue(signal).tier == UiTier.LIVE) {
                    recoveries += (now - due).coerceAtLeast(frameMs)
                    recovering.remove(signal)
                }
            }
        }
        return DegradationOutcome(monitor.counters(), recoveries)
    }

    private fun rawValue(signal: VehicleSignal, frame: Int): Double {
        val wobble = sin(frame / 9.0)
        return when (signal) {
            is VehicleSignal.Speed -> 55.0 + 25.0 * wobble
            is VehicleSignal.BatteryLevel -> 78.0 - frame * 0.01
            is VehicleSignal.RangeKm -> 320.0 - frame * 0.4 + 4.0 * wobble
            is VehicleSignal.ChargePortOpen -> if (frame % 60 < 30) 1.0 else 0.0
            is VehicleSignal.HvacTemp -> 21.5 + 1.2 * wobble
        }
    }
}
